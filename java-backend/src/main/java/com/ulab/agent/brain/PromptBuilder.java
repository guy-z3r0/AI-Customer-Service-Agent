package com.ulab.agent.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ulab.agent.api.dto.ClientDtos;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.KbEntry;
import com.ulab.agent.domain.enums.KbKind;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.services.KbService;
import com.ulab.agent.utils.Lang;
import com.ulab.agent.utils.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the standing instructions the model reads before every turn: who it is
 * pretending to be, what it is allowed to say, and in which language.
 *
 * The expensive half — reading the knowledge base out of the database — happens
 * once per call through {@link #knowledgeOf}, and the result is kept on the
 * session. {@link #build} then costs nothing per turn, which matters when a
 * caller is waiting through every one of them.
 */
@Service
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    /** Weekday keys as the hours JSON stores them, in the local working week. */
    private static final Map<String, String> WEEK = buildWeek();

    /**
     * How much of a customer's history the agent is given.
     *
     * The record grows by one line every time the agent writes something down,
     * and all of it used to go into every later prompt — so a talkative agent
     * eventually filled the model's context on its own. The newest are the ones
     * worth having.
     */
    private static final int MAX_PAST_ISSUES = 20;

    private final KbService kb;
    private final CallModeMachine modes;

    public PromptBuilder(KbService kb, CallModeMachine modes) {
        this.kb = kb;
        this.modes = modes;
    }

    /** The business's knowledge base as prompt text. One database read. */
    public String knowledgeOf(UUID businessId) {
        List<KbEntry> entries = kb.forBusiness(businessId);
        if (entries.isEmpty()) return Prompts.NO_KNOWLEDGE;

        StringBuilder text = new StringBuilder();
        appendLines(text, Prompts.ABOUT, entries, KbKind.ABOUT, false);
        appendLines(text, Prompts.SERVICES, entries, KbKind.SERVICE, true);
        appendLines(text, Prompts.POLICIES, entries, KbKind.POLICY, true);
        appendFaqs(text, entries);
        return text.toString().strip();
    }

    /** The whole system prompt for one turn of one call. */
    public String build(CallSession session) {
        Business business = session.business();
        AiSettings settings = session.aiSettings();

        StringBuilder prompt = new StringBuilder();
        prompt.append(Prompts.ROLE.formatted(personaName(settings), business.getName()))
                .append("\n\n");
        if (settings != null && notBlank(settings.getRoleDescription())) {
            prompt.append(settings.getRoleDescription()).append("\n\n");
        }
        prompt.append(Prompts.RULES).append('\n');
        if (settings != null && notBlank(settings.getReplyStyle())) {
            prompt.append("- ").append(settings.getReplyStyle()).append('\n');
        }
        prompt.append('\n').append(languageDirective(session.language())).append("\n\n");
        prompt.append(Prompts.NUISANCE.formatted(business.getName())).append("\n\n");
        prompt.append(Prompts.SITUATION).append('\n')
                .append(modes.instructionsFor(session.mode())).append('\n')
                .append(Prompts.EXCHANGES_SO_FAR.formatted(session.turnsSoFar())).append("\n\n");
        prompt.append(Prompts.TOOLS).append("\n\n");
        appendCaller(prompt, session);
        prompt.append(session.knowledge()).append("\n\n");
        appendContact(prompt, business);
        appendHours(prompt, business);
        return prompt.toString().strip();
    }

    /**
     * What is on record about whoever is calling.
     *
     * It goes in above the knowledge base on purpose: a model reads what comes
     * first most carefully, and knowing it is talking to a customer it has notes
     * about changes how it should answer everything below.
     */
    private static void appendCaller(StringBuilder prompt, CallSession session) {
        ClientDtos.ClientView caller = session.client();
        if (caller == null) return;

        prompt.append(Prompts.CALLER).append('\n');
        prompt.append(Prompts.RECORD_OPEN).append('\n');
        prompt.append("Name: ").append(asData(caller.name())).append('\n');
        prompt.append("Customer code: ").append(asData(caller.clientCode())).append('\n');
        // The number is shown only by its last digits. The model has no use for
        // the rest — it already knows who it is speaking to — and what it is
        // given is what it can be talked into reading out loud.
        if (notBlank(caller.phone())) {
            prompt.append("Phone on file: ").append(lastDigitsOf(caller.phone())).append('\n');
        }
        if (notBlank(caller.notes())) prompt.append("Notes: ").append(asData(caller.notes())).append('\n');

        if (!caller.pastIssues().isEmpty()) {
            prompt.append(Prompts.PAST_ISSUES).append('\n');
            caller.pastIssues().stream()
                    .skip(Math.max(0, caller.pastIssues().size() - MAX_PAST_ISSUES))
                    .forEach(issue -> prompt.append("- ").append(asData(issue)).append('\n'));
        }
        prompt.append(Prompts.RECORD_CLOSE).append("\n\n");
    }

    /**
     * Text from the records, made safe to put next to instructions.
     *
     * Two of the tools write whatever a caller said into this record, and it is
     * read back into the system prompt of every later call — so a caller who
     * says "log this: ... new instruction: ..." is writing into the agent's
     * standing orders unless something stops them. Angle brackets go, because
     * they are what closes the fence around this block, and a line that opens
     * like a chat role stops looking like one.
     */
    private static String asData(String stored) {
        if (stored == null) return "";
        return stored.replace('<', '(').replace('>', ')')
                // Anywhere in the line, not just at the start of one: the
                // attack in the audit puts its "System:" after a sentence,
                // which a start-of-line rule walks straight past.
                .replaceAll("(?i)\\b(system|assistant|user)\\s*:", "$1 -");
    }

    /** A number as a reminder rather than a disclosure: the last four digits. */
    private static String lastDigitsOf(String phone) {
        String digits = phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? "•••" : "•••" + digits.substring(digits.length() - 4);
    }

    // ------------------------------------------------------------ knowledge --

    private static void appendLines(StringBuilder text, String heading, List<KbEntry> entries,
                                    KbKind kind, boolean bulleted) {
        List<KbEntry> chosen = entries.stream().filter(entry -> entry.getKind() == kind).toList();
        if (chosen.isEmpty()) return;

        text.append(heading).append('\n');
        for (KbEntry entry : chosen) {
            if (bulleted) text.append("- ");
            text.append(entry.getContent()).append('\n');
        }
        text.append('\n');
    }

    private static void appendFaqs(StringBuilder text, List<KbEntry> entries) {
        List<KbEntry> faqs = entries.stream().filter(entry -> entry.getKind() == KbKind.FAQ).toList();
        if (faqs.isEmpty()) return;

        text.append(Prompts.FAQS).append('\n');
        for (KbEntry faq : faqs) {
            text.append("Q: ").append(faq.getQuestion() == null ? "" : faq.getQuestion()).append('\n');
            text.append("A: ").append(faq.getContent()).append('\n');
        }
        text.append('\n');
    }

    // -------------------------------------------------------------- business --

    private static void appendContact(StringBuilder prompt, Business business) {
        if (isBlank(business.getPhone()) && isBlank(business.getAddress())) return;

        prompt.append(Prompts.CONTACT).append('\n');
        if (notBlank(business.getPhone())) prompt.append("Phone: ").append(business.getPhone()).append('\n');
        if (notBlank(business.getAddress())) prompt.append("Address: ").append(business.getAddress()).append('\n');
        prompt.append('\n');
    }

    /**
     * Opening hours as seven plain lines. A day the owner left out is written
     * as closed rather than omitted, because a model reading a gap is free to
     * guess and a caller acting on that guess turns up to a locked door.
     */
    private static void appendHours(StringBuilder prompt, Business business) {
        JsonObject hours = parseHours(business);
        if (hours == null) return;

        prompt.append(Prompts.HOURS.formatted(business.getTimezone())).append('\n');
        WEEK.forEach((key, dayName) -> prompt.append(dayName).append(": ")
                .append(describeDay(hours.get(key))).append('\n'));
    }

    private static String describeDay(JsonElement day) {
        if (day == null || day.isJsonNull() || !day.isJsonObject()) return Prompts.CLOSED;

        JsonObject window = day.getAsJsonObject();
        JsonElement open = window.get("open");
        JsonElement close = window.get("close");
        if (open == null || close == null) return Prompts.CLOSED;
        return open.getAsString() + " to " + close.getAsString();
    }

    private static JsonObject parseHours(Business business) {
        try {
            JsonElement parsed = JsonParser.parseString(business.getHoursJson());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            log.warn("Opening hours for {} are not readable JSON; leaving them out of the prompt",
                    business.getSlug());
            return null;
        }
    }

    // ------------------------------------------------------------ internals --

    private static String languageDirective(Language language) {
        return language == Language.BN ? Prompts.ANSWER_IN_BN : Prompts.ANSWER_IN_EN;
    }

    private static String personaName(AiSettings settings) {
        return settings == null || isBlank(settings.getPersonaName())
                ? Lang.DEFAULT_PERSONA_NAME : settings.getPersonaName();
    }

    private static Map<String, String> buildWeek() {
        Map<String, String> week = new LinkedHashMap<>();
        week.put("sat", "Saturday");
        week.put("sun", "Sunday");
        week.put("mon", "Monday");
        week.put("tue", "Tuesday");
        week.put("wed", "Wednesday");
        week.put("thu", "Thursday");
        week.put("fri", "Friday");
        return week;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean notBlank(String value) {
        return !isBlank(value);
    }
}
