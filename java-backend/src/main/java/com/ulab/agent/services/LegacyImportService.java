package com.ulab.agent.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ulab.agent.Main;
import com.ulab.agent.api.dto.ImportResultView;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.KbEntry;
import com.ulab.agent.domain.enums.KbKind;
import com.ulab.agent.repo.AiSettingsRepository;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.repo.KbEntryRepository;
import com.ulab.agent.utils.FileUtils;
import com.ulab.agent.utils.Lang;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Moves the version-1 data — the hand-edited JSON files under
 * {@code java-backend/data/businesses/} — into the database.
 *
 * Safe to run as often as you like: a business whose slug already exists is
 * skipped whole, so nothing that was edited in the panel is overwritten. The
 * JSON files are left on disk untouched; from here on nothing reads them.
 */
@Service
public class LegacyImportService {

    private static final Logger log = LoggerFactory.getLogger(LegacyImportService.class);

    private final BusinessRepository businesses;
    private final AiSettingsRepository aiSettings;
    private final KbEntryRepository kbEntries;

    @PersistenceContext
    private EntityManager entityManager;

    private final String piiEncryptionKey;
    private final TransactionTemplate perBusiness;

    /**
     * @param piiEncryptionKey the same key the Flyway seed used, so every client
     *                         row decrypts the same way. Through the constructor
     *                         rather than onto the field, so it is final and
     *                         there is no window in which this class exists
     *                         without it.
     */
    public LegacyImportService(BusinessRepository businesses, AiSettingsRepository aiSettings,
                               KbEntryRepository kbEntries, PlatformTransactionManager transactions,
                               @Value("${PII_ENC_KEY:PLACEHOLDER_PII_ENC_KEY}") String piiEncryptionKey) {
        this.businesses = businesses;
        this.aiSettings = aiSettings;
        this.kbEntries = kbEntries;
        this.piiEncryptionKey = piiEncryptionKey;

        // Each folder gets its own transaction. Postgres abandons a whole
        // transaction the moment one statement fails, so sharing one across
        // every folder meant a single bad file took the rest down with it —
        // and then the outer commit failed too, which stopped the app booting.
        this.perBusiness = new TransactionTemplate(transactions);
        this.perBusiness.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ImportResultView importAll() {
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        File[] folders = Main.BUSINESSES_DIRECTORY.toFile().listFiles(File::isDirectory);
        if (folders == null) {
            log.info("No legacy data folder at {} — nothing to import", Main.BUSINESSES_DIRECTORY);
            return new ImportResultView(imported, skipped, problems);
        }

        for (File folder : folders) {
            try {
                perBusiness.executeWithoutResult(status -> importOne(folder.toPath(), imported, skipped));
            } catch (RuntimeException e) {
                log.warn("Could not import legacy business '{}': {}", folder.getName(), e.toString());
                problems.add(folder.getName() + " — " + e.getMessage());
            }
        }
        log.info("Legacy import finished: {} imported, {} skipped, {} problem(s)",
                imported.size(), skipped.size(), problems.size());
        return new ImportResultView(imported, skipped, problems);
    }

    // ------------------------------------------------------------ one folder --

    private void importOne(Path folder, List<String> imported, List<String> skipped) {
        JsonObject businessJson = readJson(folder.resolve("business.json"));
        if (businessJson == null) return;  // not a business folder, nothing to say

        String name = text(businessJson, "businessName", folder.getFileName().toString());
        String slug = slugify(name);
        if (businesses.existsBySlug(slug)) {
            skipped.add(name);
            return;
        }

        JsonObject details = object(businessJson, "businessDetails");
        Business business = buildBusiness(slug, name, details);
        businesses.save(business);

        importAiSettings(folder, business.getId(), name);
        importKnowledge(folder, business.getId(), details);
        importClients(folder, business.getId());

        imported.add(name);
        log.info("Imported legacy business '{}' as slug '{}'", name, slug);
    }

    private Business buildBusiness(String slug, String name, JsonObject details) {
        Business business = new Business();
        business.setSlug(slug);
        business.setName(name);
        business.setActive(false);  // importing must never steal the active flag
        if (details != null) {
            business.setPhone(firstOfArray(details, "telephoneNumber"));
            business.setEmail(text(details, "emailAddress", null));
            business.setAddress(text(details, "businessAddress", null));
            // v1 kept opening hours as one free-text line. It is preserved as a
            // note so nothing is lost; Phase 5's editor replaces it with real days.
            String hours = text(details, "businessHours", null);
            if (hours != null) business.setHoursJson(noteJson(hours));
        }
        return business;
    }

    private void importAiSettings(Path folder, UUID businessId, String businessName) {
        JsonObject json = readJson(folder.resolve("ai-settings.json"));
        AiSettings settings = new AiSettings();
        settings.setBusinessId(businessId);
        settings.setPersonaName(Lang.DEFAULT_PERSONA_NAME);
        settings.setRoleDescription(json == null
                ? Lang.DEFAULT_ROLE_DESCRIPTION
                : text(json, "roleInstructions", Lang.DEFAULT_ROLE_DESCRIPTION));
        settings.setReplyStyle(json == null
                ? Lang.DEFAULT_REPLY_STYLE
                : text(json, "replyInstructions", Lang.DEFAULT_REPLY_STYLE));
        settings.setGreetingEn(String.format(Lang.DEFAULT_GREETING_EN, businessName));
        settings.setGreetingBn(String.format(Lang.DEFAULT_GREETING_BN, businessName));
        aiSettings.save(settings);
    }

    /**
     * intelligence.json holds the knowledge base as four differently shaped
     * lists, which all flatten into kb_entry rows of the matching kind.
     */
    private void importKnowledge(Path folder, UUID businessId, JsonObject details) {
        JsonObject json = readJson(folder.resolve("intelligence.json"));

        String about = text(json, "about", null);
        if (about == null && details != null) about = text(details, "businessDescription", null);
        if (about != null) kbEntries.save(entry(businessId, KbKind.ABOUT, null, about, 0));

        if (json == null) return;
        saveList(json, "services", businessId, KbKind.SERVICE);
        saveList(json, "policies", businessId, KbKind.POLICY);

        int faqOrder = 0;
        for (JsonElement element : array(json, "faqs")) {
            if (!element.isJsonObject()) continue;
            JsonObject faq = element.getAsJsonObject();
            String question = text(faq, "question", null);
            String answer = text(faq, "answer", null);
            if (question == null || answer == null) continue;
            kbEntries.save(entry(businessId, KbKind.FAQ, question, answer, faqOrder++));
        }
    }

    private void saveList(JsonObject json, String field, UUID businessId, KbKind kind) {
        int order = 0;
        for (JsonElement element : array(json, field)) {
            if (!element.isJsonPrimitive()) continue;
            kbEntries.save(entry(businessId, kind, null, element.getAsString(), order++));
        }
    }

    /**
     * Clients go in through raw SQL because their phone and email are encrypted
     * by Postgres itself (pgp_sym_encrypt), which JPA has no way to express.
     */
    private void importClients(Path folder, UUID businessId) {
        JsonObject json = readJson(folder.resolve("clients.json"));
        if (json == null) return;

        // The business row is still sitting unsent in the persistence context;
        // raw SQL does not see it, and the foreign key would fail. Push it out first.
        entityManager.flush();

        for (JsonElement element : array(json, "clients")) {
            if (!element.isJsonObject()) continue;
            JsonObject client = element.getAsJsonObject();
            String code = text(client, "clientId", null);
            String name = text(client, "name", null);
            if (code == null || name == null) continue;

            entityManager.createNativeQuery("""
                    INSERT INTO client (business_id, client_code, name, phone_enc, phone_hash,
                                        email_enc, notes, past_issues_json)
                    VALUES (cast(:businessId as uuid), cast(:code as text), cast(:name as text),
                            pgp_sym_encrypt(cast(:phone as text), cast(:key as text)),
                            decode(cast(:phoneHash as text), 'hex'),
                            pgp_sym_encrypt(cast(:email as text), cast(:key as text)),
                            cast(:notes as text), cast(:pastIssues as jsonb))
                    ON CONFLICT (business_id, client_code) DO NOTHING
                    """)
                    .setParameter("businessId", businessId.toString())
                    .setParameter("code", code)
                    .setParameter("name", name)
                    .setParameter("phone", text(client, "phoneNumber", null))
                    // Imported rows need the lookup value too, or an inbound call
                    // from one of them falls back to decrypting the whole list.
                    .setParameter("phoneHash", ClientService.phoneHash(
                            text(client, "phoneNumber", null)))
                    .setParameter("email", text(client, "email", null))
                    .setParameter("key", piiEncryptionKey)
                    .setParameter("notes", text(client, "notes", null))
                    .setParameter("pastIssues", array(client, "pastIssues").toString())
                    .executeUpdate();
        }
    }

    // ---------------------------------------------------------- json helpers --

    private static JsonObject readJson(Path file) {
        if (!Files.exists(file)) return null;
        return FileUtils.getJsonObject(file.getFileName().toString(), file, false);
    }

    private static JsonObject object(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        return (element != null && element.isJsonObject()) ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String field) {
        JsonElement element = parent == null ? null : parent.get(field);
        return (element != null && element.isJsonArray()) ? element.getAsJsonArray() : new JsonArray();
    }

    private static String text(JsonObject parent, String field, String fallback) {
        if (parent == null) return fallback;
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        String value = element.getAsString();
        return value.isBlank() ? fallback : value;
    }

    private static String firstOfArray(JsonObject parent, String field) {
        JsonArray values = array(parent, field);
        return values.isEmpty() ? null : values.get(0).getAsString();
    }

    private static KbEntry entry(UUID businessId, KbKind kind, String question,
                                 String content, int sortOrder) {
        KbEntry kbEntry = new KbEntry();
        kbEntry.setBusinessId(businessId);
        kbEntry.setKind(kind);
        kbEntry.setQuestion(question);
        kbEntry.setContent(content);
        kbEntry.setSortOrder(sortOrder);
        return kbEntry;
    }

    /** Wraps free text as {"note": "..."} with the quoting JSON needs. */
    private static String noteJson(String note) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("note", note);
        return wrapper.toString();
    }

    private static String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "imported-business" : slug;
    }
}
