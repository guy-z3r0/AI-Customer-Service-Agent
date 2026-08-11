package com.ulab.agent.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulab.agent.api.dto.BusinessUpsertRequest;
import com.ulab.agent.api.dto.BusinessView;
import com.ulab.agent.api.dto.EditorDtos;
import com.ulab.agent.api.dto.TransferDtos;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.repo.AiSettingsRepository;
import com.ulab.agent.repo.EscalationContactRepository;
import com.ulab.agent.repo.KbEntryRepository;
import com.ulab.agent.utils.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A business as a file, out and back in again.
 *
 * Setting a business up is an afternoon of typing — about, services, policies,
 * questions, persona, hours, who takes a handover. This is how that afternoon
 * moves: to another machine, into a backup, or to somebody else who wants to
 * start from yours rather than from an empty editor.
 *
 * Nothing here validates or sanitises anything itself. Every write goes through
 * {@link BusinessService} and {@link KbService}, the same two the editor's own
 * forms post to, so a file cannot put anything into the database that could not
 * be typed into the panel. That is the whole reason this class is a coordinator
 * and not a writer.
 */
@Service
public class BusinessTransferService {

    private static final Logger log = LoggerFactory.getLogger(BusinessTransferService.class);

    /** An empty jsonb object: what a business with no opening hours holds. */
    private static final String NO_HOURS = "{}";

    private final BusinessService businesses;
    private final KbService kb;
    private final AiSettingsRepository aiSettings;
    private final KbEntryRepository kbEntries;
    private final EscalationContactRepository contacts;
    private final ObjectMapper json = new ObjectMapper();

    public BusinessTransferService(BusinessService businesses, KbService kb,
                                   AiSettingsRepository aiSettings, KbEntryRepository kbEntries,
                                   EscalationContactRepository contacts) {
        this.businesses = businesses;
        this.kb = kb;
        this.aiSettings = aiSettings;
        this.kbEntries = kbEntries;
        this.contacts = contacts;
    }

    // ---------------------------------------------------------------- export --

    @Transactional(readOnly = true)
    public TransferDtos.BusinessDocument export(UUID businessId) {
        BusinessView business = businesses.get(businessId);
        return new TransferDtos.BusinessDocument(
                TransferDtos.FORMAT,
                TransferDtos.VERSION,
                Instant.now().toString(),
                business.slug(),
                businessPart(business),
                personaPart(businessId),
                knowledgeParts(businessId),
                contactParts(businessId));
    }

    /** The name a browser should save the file under. */
    public static String fileNameFor(String slug) {
        String safe = slug == null ? "" : slug.replaceAll("[^a-zA-Z0-9._-]", "-");
        return "business-" + (safe.isBlank() ? "export" : safe) + ".json";
    }

    private TransferDtos.BusinessPart businessPart(BusinessView business) {
        return new TransferDtos.BusinessPart(business.name(), business.phone(), business.email(),
                business.address(), business.timezone(), readHours(business.hoursJson()));
    }

    /**
     * A business that has never had a persona exports none, rather than one
     * invented on the way out — reading a record must not create one.
     */
    private TransferDtos.PersonaPart personaPart(UUID businessId) {
        return aiSettings.findById(businessId).map(BusinessTransferService::toPart).orElse(null);
    }

    private static TransferDtos.PersonaPart toPart(AiSettings settings) {
        return new TransferDtos.PersonaPart(settings.getPersonaName(),
                settings.getRoleDescription(), settings.getReplyStyle(), settings.getGreetingEn(),
                settings.getGreetingBn(), settings.getProviderOverride(),
                settings.getModelOverride(), settings.getTemperature(),
                settings.getMaxHistoryTurns());
    }

    private List<TransferDtos.KnowledgePart> knowledgeParts(UUID businessId) {
        return kb.listForPanel(businessId).stream()
                .map(entry -> new TransferDtos.KnowledgePart(entry.kind(), entry.question(),
                        entry.content(), entry.sortOrder()))
                .toList();
    }

    private List<TransferDtos.ContactPart> contactParts(UUID businessId) {
        return businesses.escalationContacts(businessId).stream()
                .map(contact -> new TransferDtos.ContactPart(contact.name(), contact.email(),
                        contact.priority()))
                .toList();
    }

    // ---------------------------------------------------------------- import --

    /**
     * Reads a file back in, either as a new business or over an existing one.
     *
     * One transaction for the lot: a file that fails halfway through — a bad
     * weekday, a knowledge entry over the length limit — leaves the database
     * exactly as it was, rather than a half-built business somebody then has to
     * find and delete.
     */
    @Transactional
    public TransferDtos.ImportResultView importDocument(TransferDtos.ImportRequest request) {
        TransferDtos.BusinessDocument document = request.document();
        requireReadableFormat(document);

        TransferDtos.ImportMode mode = modeOf(request.mode());
        UUID businessId = mode == TransferDtos.ImportMode.ADD
                ? added(document)
                : replaced(request.targetId(), document);

        writePersona(businessId, document.persona());
        int entries = writeKnowledge(businessId, document.knowledge());
        int handovers = writeContacts(businessId, document.escalationContacts());

        log.info("Imported a business file as {} ({}): {} knowledge entries, {} contacts",
                businessId, mode, entries, handovers);
        return new TransferDtos.ImportResultView(businessId, document.business().name(),
                mode.name().toLowerCase(), entries, handovers);
    }

    /**
     * The file is refused before anything is written when it is not one of ours.
     *
     * A person picking a file from a folder picks the wrong one sooner or later,
     * and the difference between "that is not a business file" and a half-built
     * business made from somebody's invoice is worth these six lines.
     */
    private static void requireReadableFormat(TransferDtos.BusinessDocument document) {
        if (!TransferDtos.FORMAT.equals(document.format())) {
            throw badRequest(Lang.ERR_IMPORT_FORMAT);
        }
        if (document.version() > TransferDtos.VERSION) {
            throw badRequest(Lang.ERR_IMPORT_VERSION);
        }
    }

    /** A new business, with a handle of this installation's making. */
    private UUID added(TransferDtos.BusinessDocument document) {
        return businesses.create(upsertFrom(document)).id();
    }

    /**
     * An existing business, made to match the file.
     *
     * Its handle, its customers and its call history stay: they are facts about
     * this installation rather than contents of the file. Everything the editor
     * can reach is replaced, which is why the panel asks first.
     */
    private UUID replaced(UUID targetId, TransferDtos.BusinessDocument document) {
        if (targetId == null) throw badRequest(Lang.ERR_IMPORT_NO_TARGET);

        businesses.update(targetId, upsertFrom(document));
        kbEntries.deleteByBusinessId(targetId);
        contacts.deleteByBusinessId(targetId);
        return targetId;
    }

    private static BusinessUpsertRequest upsertFrom(TransferDtos.BusinessDocument document) {
        TransferDtos.BusinessPart part = document.business();
        return new BusinessUpsertRequest(part.name(), part.phone(), part.email(), part.address(),
                part.timezone(), writeHours(part.hours()));
    }

    private void writePersona(UUID businessId, TransferDtos.PersonaPart persona) {
        // A file without a persona leaves the one already there alone — which,
        // for a business just created, is the sensible default it was given.
        if (persona == null) return;

        businesses.updateAiSettings(businessId, new EditorDtos.AiSettingsRequest(
                persona.personaName(), persona.roleDescription(), persona.replyStyle(),
                persona.greetingEn(), persona.greetingBn(), persona.providerOverride(),
                persona.modelOverride(), persona.temperature(), persona.maxHistoryTurns()));
    }

    private int writeKnowledge(UUID businessId, List<TransferDtos.KnowledgePart> knowledge) {
        if (knowledge == null) return 0;
        for (TransferDtos.KnowledgePart entry : knowledge) {
            kb.create(businessId, new EditorDtos.KbUpsertRequest(entry.kind(), entry.question(),
                    entry.content(), entry.sortOrder()));
        }
        return knowledge.size();
    }

    private int writeContacts(UUID businessId, List<TransferDtos.ContactPart> handovers) {
        if (handovers == null) return 0;
        for (TransferDtos.ContactPart contact : handovers) {
            businesses.addEscalationContact(businessId, new EditorDtos.EscalationRequest(
                    contact.name(), contact.email(), contact.priority()));
        }
        return handovers.size();
    }

    // ------------------------------------------------------------------ hours --

    /**
     * The stored hours as real JSON rather than as the string the column holds.
     *
     * A file somebody is expected to read and edit should have the days nested
     * in it, not a quoted blob of JSON inside JSON.
     */
    private JsonNode readHours(String hoursJson) {
        try {
            JsonNode parsed = json.readTree(hoursJson == null ? NO_HOURS : hoursJson);
            return parsed.isObject() ? parsed : null;
        } catch (Exception notJson) {
            // Only reachable for a row written before this column was jsonb.
            log.debug("A business's stored hours could not be read as JSON");
            return null;
        }
    }

    /** @return what belongs in the jsonb column; never null, because it is not nullable */
    private static String writeHours(JsonNode hours) {
        if (hours == null || !hours.isObject()) return NO_HOURS;
        return hours.toString();
    }

    // -------------------------------------------------------------- internals --

    private static TransferDtos.ImportMode modeOf(String raw) {
        try {
            return TransferDtos.ImportMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException notAMode) {
            throw badRequest(Lang.ERR_IMPORT_MODE);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
