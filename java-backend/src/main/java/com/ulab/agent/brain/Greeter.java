package com.ulab.agent.brain;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.services.CallLogService;
import com.ulab.agent.utils.Lang;
import org.springframework.stereotype.Component;

/**
 * The first thing a caller hears, and the question that follows it.
 *
 * This is its own class because opening a call is the one moment with rules
 * nothing else in the conversation has: the agent says the same thing twice, in
 * two languages, before anybody has chosen one — and the microphone must stay
 * shut until both halves have been read.
 */
@Component
public class Greeter {

    /** Roles as the model layer names them, which are not the transcript's names. */
    private static final String AGENT = "assistant";

    private final CallLogService callLog;

    public Greeter(CallLogService callLog) {
        this.callLog = callLog;
    }

    /** Greets the caller and asks which language they want. */
    public void greet(CallSession session) {
        String greeting = greetingFor(session);
        session.remember(AGENT, greeting);
        callLog.record(session.callId(), agentLine(session, greeting));
        // last=false: the language question is coming, and the microphone stays
        // shut until the agent has finished asking it.
        session.send("greeting", "text", greeting,
                "language", session.language().code(), "last", false);
        askWhichLanguage(session);
    }

    /**
     * The one moment in a call where the agent says the same thing twice.
     *
     * Nobody knows yet which language the caller wants, so the question goes out
     * once in each — as two sentences, each tagged with its own language, so the
     * voice server reads the Bangla half in a Bangla voice rather than putting
     * an English accent on it.
     */
    private void askWhichLanguage(CallSession session) {
        String key = "voice.language_question";
        session.send("say", "seq", 0, "text", Lang.ui("en").get(key), "language", "en",
                "last", false);
        session.send("say", "seq", 0, "text", Lang.ui("bn").get(key), "language", "bn",
                "last", true);

        String both = Lang.bilingual(key);
        session.remember(AGENT, both);
        callLog.record(session.callId(), agentLine(session, both));
    }

    private static String greetingFor(CallSession session) {
        AiSettings settings = session.aiSettings();
        boolean bangla = session.language() == Language.BN;
        String greeting = settings == null ? null
                : (bangla ? settings.getGreetingBn() : settings.getGreetingEn());
        if (greeting != null && !greeting.isBlank()) return greeting;

        String template = bangla ? Lang.DEFAULT_GREETING_BN : Lang.DEFAULT_GREETING_EN;
        return template.formatted(session.business().getName());
    }

    private static CallDtos.LineToStore agentLine(CallSession session, String text) {
        return CallDtos.LineToStore.agent(text, session.language().code(),
                session.mode().name(), 0, null, null, null);
    }
}
