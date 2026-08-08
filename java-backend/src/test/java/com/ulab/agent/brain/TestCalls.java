package com.ulab.agent.brain;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.api.dto.ClientDtos;
import com.ulab.agent.brain.llm.LlmRouter;
import com.ulab.agent.brain.tools.ToolExecutor;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.ModeTransition;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.domain.enums.Telephony;
import com.ulab.agent.services.CallLogService;
import com.ulab.agent.services.ClientService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A live call with nothing behind it — no database, no websocket, no model.
 *
 * Everything the brain writes down or sends is collected in lists instead, so a
 * test can read what a call actually did rather than what it was told to do.
 */
public final class TestCalls {

    private TestCalls() {
    }

    /** A call, the mode machine over it, and somewhere to see what both did. */
    public record Wiring(CallSession session, Recorder log, Outbox outbox, Customers customers,
                         CallModeMachine modes, ToolExecutor executor) {
    }

    public static Wiring wire(CallMode mode, Language language) {
        Recorder log = new Recorder();
        Outbox outbox = new Outbox();
        Customers customers = new Customers();
        CallModeMachine modes = new CallModeMachine(log);
        CallSession session = session(mode, language, outbox);
        return new Wiring(session, log, outbox, customers, modes,
                new ToolExecutor(modes, log, customers));
    }

    /** A customer list held in memory, with no database and no encryption. */
    public static final class Customers extends ClientService {

        public final List<ClientDtos.ClientView> records = new ArrayList<>();

        public Customers() {
            // No repository and no database: every method that would touch one
            // is overridden below. The key is a constructor argument now rather
            // than a field Spring fills in afterwards, which is what lets this
            // class exist at all without a running context.
            super(null, "TEST_ONLY_NOT_A_KEY");
        }

        /** Puts somebody on the books before a test starts. */
        public ClientDtos.ClientView add(String code, String name, String phone,
                                         List<String> pastIssues) {
            ClientDtos.ClientView client = new ClientDtos.ClientView(UUID.randomUUID(),
                    UUID.randomUUID(), code, name, phone, null, null, pastIssues, true,
                    Instant.now());
            records.add(client);
            return client;
        }

        @Override
        public Optional<ClientDtos.ClientView> byCode(UUID businessId, String clientCode) {
            if (clientCode == null || clientCode.isBlank()) return Optional.empty();
            return records.stream()
                    .filter(client -> client.clientCode().equalsIgnoreCase(clientCode.trim()))
                    .findFirst();
        }

        @Override
        public Optional<ClientDtos.ClientView> byPhone(UUID businessId, String phone) {
            if (phone == null || phone.isBlank()) return Optional.empty();
            String digits = phone.replaceAll("\\D", "");
            return records.stream()
                    .filter(client -> client.phone() != null)
                    .filter(client -> client.phone().replaceAll("\\D", "").endsWith(digits))
                    .findFirst();
        }

        @Override
        public ClientDtos.ClientView create(UUID businessId,
                                            ClientDtos.ClientUpsertRequest request) {
            return add("C%03d".formatted(records.size() + 1), request.name(), request.phone(),
                    request.pastIssues() == null ? List.of() : request.pastIssues());
        }

        @Override
        public ClientDtos.ClientView appendPastIssue(UUID clientId, String issue) {
            ClientDtos.ClientView before = records.stream()
                    .filter(client -> client.id().equals(clientId)).findFirst().orElseThrow();
            List<String> issues = new ArrayList<>(before.pastIssues());
            issues.add(issue);

            records.remove(before);
            ClientDtos.ClientView after = new ClientDtos.ClientView(before.id(),
                    before.businessId(), before.clientCode(), before.name(), before.phone(),
                    before.email(), before.notes(), issues, true, before.createdAt());
            records.add(after);
            return after;
        }
    }

    public static CallSession session(CallMode mode, Language language, Outbox outbox) {
        CallSession session = new CallSession(UUID.randomUUID(), business(), aiSettings(),
                "About the business:\nAn example.", new LlmRouter.Selection("gemini", "m", 0.7),
                Telephony.BROWSER, language, new Object(), outbox);
        session.setMode(mode);
        return session;
    }

    /** A call log that keeps what it was given instead of storing it. */
    public static final class Recorder extends CallLogService {

        public final List<ModeTransition> modeChanges = new ArrayList<>();
        public final List<Language> languageChanges = new ArrayList<>();
        public final List<String> identified = new ArrayList<>();
        public final List<CallDtos.LineToStore> lines = new ArrayList<>();

        public Recorder() {
            super(null, null, null, null, null, null);
        }

        @Override
        public int record(UUID callId, CallDtos.LineToStore line) {
            lines.add(line);
            return lines.size();
        }

        @Override
        public void recordModeChange(ModeTransition transition) {
            modeChanges.add(transition);
        }

        @Override
        public void recordLanguageChange(UUID callId, Language language) {
            languageChanges.add(language);
        }

        @Override
        public void recordClient(UUID callId, UUID clientId, String name) {
            identified.add(name);
        }
    }

    /** Everything the brain sent down the call's websocket, in order. */
    public static final class Outbox implements Consumer<Map<String, Object>> {

        public final List<Map<String, Object>> sent = new ArrayList<>();

        @Override
        public void accept(Map<String, Object> message) {
            sent.add(new LinkedHashMap<>(message));
        }

        public List<Map<String, Object>> ofType(String type) {
            return sent.stream().filter(message -> type.equals(message.get("type"))).toList();
        }
    }

    public static Business business() {
        Business business = new Business();
        business.setId(UUID.randomUUID());
        business.setSlug("example-shop");
        business.setName("Example Shop");
        business.setTimezone("Asia/Dhaka");
        business.setHoursJson("{}");
        return business;
    }

    public static AiSettings aiSettings() {
        AiSettings settings = new AiSettings();
        settings.setPersonaName("Ayesha");
        settings.setRoleDescription("You answer the phone for Example Shop.");
        settings.setReplyStyle("Warm and short.");
        settings.setGreetingEn("Hello, you have reached Example Shop.");
        settings.setGreetingBn("হ্যালো, আপনি এক্সাম্পল শপে কল করেছেন।");
        return settings;
    }
}
