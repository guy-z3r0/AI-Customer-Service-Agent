package com.ulab.agent.services;

import com.ulab.agent.api.dto.BusinessUpsertRequest;
import com.ulab.agent.api.dto.BusinessView;
import com.ulab.agent.api.dto.EditorDtos;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.EscalationContact;
import com.ulab.agent.repo.AiSettingsRepository;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.repo.ClientRepository;
import com.ulab.agent.repo.EscalationContactRepository;
import com.ulab.agent.repo.KbEntryRepository;
import com.ulab.agent.utils.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Everything the panel does to businesses: list them, read one, edit one, and
 * choose which one is "active" — the business whose knowledge and persona the
 * next call will use.
 */
@Service
public class BusinessService {

    private static final Logger log = LoggerFactory.getLogger(BusinessService.class);

    private final BusinessRepository businesses;
    private final KbEntryRepository kbEntries;
    private final ClientRepository clients;
    private final AiSettingsRepository aiSettings;
    private final EscalationContactRepository contacts;

    public BusinessService(BusinessRepository businesses, KbEntryRepository kbEntries,
                           ClientRepository clients, AiSettingsRepository aiSettings,
                           EscalationContactRepository contacts) {
        this.businesses = businesses;
        this.kbEntries = kbEntries;
        this.clients = clients;
        this.aiSettings = aiSettings;
        this.contacts = contacts;
    }

    @Transactional(readOnly = true)
    public List<BusinessView> list() {
        return businesses.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public BusinessView get(UUID id) {
        return toView(require(id));
    }

    /** The business new calls are answered as, or empty when none is chosen. */
    @Transactional(readOnly = true)
    public BusinessView active() {
        return businesses.findFirstByActiveTrue().map(this::toView).orElse(null);
    }

    @Transactional
    public BusinessView create(BusinessUpsertRequest request) {
        Business business = new Business();
        business.setSlug(uniqueSlug(request.name()));
        apply(business, request);
        businesses.save(business);
        log.info("Business created: {}", business.getSlug());
        return toView(business);
    }

    @Transactional
    public BusinessView update(UUID id, BusinessUpsertRequest request) {
        Business business = require(id);
        apply(business, request);
        businesses.save(business);
        return toView(business);
    }

    /**
     * Deleting a business takes its knowledge, clients and call history with it —
     * the foreign keys cascade. The panel asks for confirmation first.
     */
    @Transactional
    public void delete(UUID id) {
        Business business = require(id);
        businesses.delete(business);
        log.info("Business deleted: {}", business.getSlug());
    }

    @Transactional
    public BusinessView activate(UUID id) {
        businesses.deactivateAllExcept(id);
        Business business = require(id);
        business.setActive(true);
        businesses.save(business);
        log.info("Active business is now: {}", business.getSlug());
        return toView(business);
    }

    // ---------------------------------------------------------- ai settings --

    /**
     * The agent's personality for a business. A business that has never been
     * given one gets a sensible default rather than a 404 — the editor has to
     * have something to open.
     */
    @Transactional
    public EditorDtos.AiSettingsView aiSettings(UUID businessId) {
        return toView(settingsOrDefault(businessId));
    }

    @Transactional
    public EditorDtos.AiSettingsView updateAiSettings(UUID businessId,
                                                      EditorDtos.AiSettingsRequest request) {
        AiSettings settings = settingsOrDefault(businessId);
        settings.setPersonaName(request.personaName().trim());
        settings.setRoleDescription(request.roleDescription().trim());
        settings.setReplyStyle(request.replyStyle().trim());
        settings.setGreetingEn(request.greetingEn().trim());
        settings.setGreetingBn(request.greetingBn().trim());
        settings.setProviderOverride(blankToNull(request.providerOverride()));
        settings.setModelOverride(blankToNull(request.modelOverride()));
        if (request.temperature() != null) settings.setTemperature(request.temperature());
        if (request.maxHistoryTurns() != null) settings.setMaxHistoryTurns(request.maxHistoryTurns());

        aiSettings.save(settings);
        log.info("AI settings updated for business {}", businessId);
        return toView(settings);
    }

    private AiSettings settingsOrDefault(UUID businessId) {
        Business business = require(businessId);
        return aiSettings.findById(businessId).orElseGet(() -> {
            AiSettings fresh = new AiSettings();
            fresh.setBusinessId(businessId);
            fresh.setPersonaName(Lang.DEFAULT_PERSONA_NAME);
            fresh.setRoleDescription(Lang.DEFAULT_ROLE_DESCRIPTION);
            fresh.setReplyStyle(Lang.DEFAULT_REPLY_STYLE);
            fresh.setGreetingEn(Lang.DEFAULT_GREETING_EN.formatted(business.getName()));
            fresh.setGreetingBn(Lang.DEFAULT_GREETING_BN.formatted(business.getName()));
            return aiSettings.save(fresh);
        });
    }

    // -------------------------------------------------- escalation contacts --

    @Transactional(readOnly = true)
    public List<EditorDtos.EscalationView> escalationContacts(UUID businessId) {
        return contacts.findByBusinessIdOrderByPriorityAsc(businessId).stream()
                .map(BusinessService::toView).toList();
    }

    @Transactional
    public EditorDtos.EscalationView addEscalationContact(UUID businessId,
                                                          EditorDtos.EscalationRequest request) {
        require(businessId);
        EscalationContact contact = new EscalationContact();
        contact.setBusinessId(businessId);
        apply(contact, request);
        contacts.save(contact);
        return toView(contact);
    }

    @Transactional
    public EditorDtos.EscalationView updateEscalationContact(UUID contactId,
                                                             EditorDtos.EscalationRequest request) {
        EscalationContact contact = contacts.findById(contactId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, Lang.ERR_CONTACT_NOT_FOUND));
        apply(contact, request);
        contacts.save(contact);
        return toView(contact);
    }

    @Transactional
    public void deleteEscalationContact(UUID contactId) {
        contacts.deleteById(contactId);
    }

    // ------------------------------------------------------------ internals --

    private static void apply(EscalationContact contact, EditorDtos.EscalationRequest request) {
        contact.setName(oneLine(request.name()));
        contact.setEmail(oneLine(request.email()));
        contact.setPriority(request.priority() == null ? 1 : request.priority());
    }

    /**
     * A name or address with no line breaks in it.
     *
     * All three of these end up in an email — the business name in the subject
     * of the escalation, the contact's name and address in its headers. A
     * carriage return in a header value is how a second header gets added to
     * somebody else's message. Jakarta Mail folds and encodes subjects, so this
     * was unlikely to be exploitable; taking the characters out on the way in
     * costs a line and means nobody downstream has to know that.
     */
    private static String oneLine(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static EditorDtos.EscalationView toView(EscalationContact contact) {
        return new EditorDtos.EscalationView(contact.getId(), contact.getName(),
                contact.getEmail(), contact.getPriority());
    }

    private static EditorDtos.AiSettingsView toView(AiSettings settings) {
        return new EditorDtos.AiSettingsView(settings.getPersonaName(),
                settings.getRoleDescription(), settings.getReplyStyle(), settings.getGreetingEn(),
                settings.getGreetingBn(), settings.getProviderOverride(),
                settings.getModelOverride(), settings.getTemperature(),
                settings.getMaxHistoryTurns());
    }

    private Business require(UUID id) {
        return businesses.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, Lang.ERR_BUSINESS_NOT_FOUND));
    }

    private void apply(Business business, BusinessUpsertRequest request) {
        // The name reaches the subject line of the escalation email — see oneLine.
        business.setName(oneLine(request.name()));
        business.setPhone(blankToNull(request.phone()));
        business.setEmail(blankToNull(request.email()));
        business.setAddress(blankToNull(request.address()));
        if (request.timezone() != null && !request.timezone().isBlank()) {
            business.setTimezone(request.timezone().trim());
        }
        if (request.hoursJson() != null && !request.hoursJson().isBlank()) {
            business.setHoursJson(request.hoursJson());
        }
    }

    private BusinessView toView(Business b) {
        return new BusinessView(b.getId(), b.getSlug(), b.getName(), b.getPhone(), b.getEmail(),
                b.getAddress(), b.getTimezone(), b.getHoursJson(), b.isActive(),
                kbEntries.countByBusinessId(b.getId()), clients.countByBusinessId(b.getId()));
    }

    /** Turns a name into a url-safe handle, adding -2, -3… if it is taken. */
    private String uniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "business";
        String candidate = base;
        int suffix = 2;
        while (businesses.existsBySlug(candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
