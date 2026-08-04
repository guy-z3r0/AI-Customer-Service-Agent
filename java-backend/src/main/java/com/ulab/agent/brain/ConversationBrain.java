package com.ulab.agent.brain;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.brain.llm.LlmRequest;
import com.ulab.agent.brain.llm.LlmRouter;
import com.ulab.agent.brain.tools.ToolExecutor;
import com.ulab.agent.brain.tools.ToolRegistry;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.CallRecord;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.repo.AiSettingsRepository;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.services.CallLogService;
import com.ulab.agent.services.ClientService;
import com.ulab.agent.utils.Lang;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The conversation itself. One turn is: the caller finished a sentence, the
 * model is asked what to say, and each sentence of the answer is handed to the
 * voice server the moment it is complete rather than when the whole reply is.
 *
 * This class owns the call — starting it, ending it, what it says outside a
 * turn, and which of those things get written down. {@link TurnRunner} owns
 * what happens inside one.
 *
 * A turn runs on its own thread. The websocket carrying the call must stay
 * free to receive — the caller can hang up while the model is still thinking,
 * and a socket blocked on an API call would not hear them.
 */
@Service
public class ConversationBrain {

    private static final Logger log = LoggerFactory.getLogger(ConversationBrain.class);

    /** Roles as the model layer names them, which are not the transcript's names. */
    private static final String CALLER = "user";
    private static final String AGENT = "assistant";

    /** How long a call survives without a voice server before it is written off. */
    private static final int LINK_GRACE_SECONDS = 6;

    /** Utterances the recogniser makes nothing of before the agent asks again. */
    private static final int UNHEARD_BEFORE_REPROMPT = 2;

    private final CallRegistry registry;
    private final PromptBuilder prompts;
    private final LlmRouter router;
    private final CallLogService callLog;
    private final BusinessRepository businesses;
    private final AiSettingsRepository aiSettings;
    private final CallModeMachine modes;
    private final ToolRegistry tools;
    private final ToolExecutor toolExecutor;
    private final ClientService clients;

    private final ExecutorService turnWorkers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService linkGrace = Executors.newSingleThreadScheduledExecutor();

    public ConversationBrain(CallRegistry registry, PromptBuilder prompts, LlmRouter router,
                             CallLogService callLog, BusinessRepository businesses,
                             AiSettingsRepository aiSettings, CallModeMachine modes,
                             ToolRegistry tools, ToolExecutor toolExecutor,
                             ClientService clients) {
        this.clients = clients;
        this.registry = registry;
        this.prompts = prompts;
        this.router = router;
        this.callLog = callLog;
        this.businesses = businesses;
        this.aiSettings = aiSettings;
        this.modes = modes;
        this.tools = tools;
        this.toolExecutor = toolExecutor;
    }

    @PreDestroy
    void stopWorkers() {
        turnWorkers.shutdownNow();
        linkGrace.shutdownNow();
    }

    // ----------------------------------------------------------- lifecycle --

    /**
     * Reads the business behind the call, greets the caller and returns the
     * session everything after this hangs off.
     *
     * A voice server that lost its socket and dialled back in sends call_start
     * again. That is a reconnection, not a new call: the session it left behind
     * is handed back with its history and its turn count intact, and the caller
     * is not greeted a second time.
     */
    public CallSession onCallStart(UUID callId, String languageHint, Object link,
                                   Consumer<Map<String, Object>> outbound) {
        CallSession resumed = registry.get(callId);
        if (resumed != null) {
            resumed.attach(link, outbound);
            log.info("[{}] the voice server reconnected", callId);
            return resumed;
        }

        CallRecord record = callLog.require(callId);
        Business business = businesses.findById(record.getBusinessId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, Lang.ERR_NO_ACTIVE_BUSINESS));
        AiSettings settings = aiSettings.findById(business.getId()).orElse(null);

        CallSession session = new CallSession(callId, business, settings,
                prompts.knowledgeOf(business.getId()), router.select(settings),
                record.getTelephony(), Language.of(languageHint), link, outbound);
        // A call dialled as a known customer opens knowing who it is with. Every
        // other call opens as a stranger and can be recognised part-way through.
        if (record.getClientId() != null) session.setClient(clients.get(record.getClientId()));
        session.setMode(modes.initialMode(session.client() != null));
        registry.add(session);
        modes.open(session);

        log.info("Call {} is {} answering as {} on {}", callId, business.getSlug(),
                session.selection().providerId(), session.selection().model());
        greet(session);
        return session;
    }

    public void onTranscriptPartial(UUID callId, String text) {
        CallSession session = registry.get(callId);
        if (session != null) session.touch();
        callLog.partial(callId, text);
    }

    /** The caller finished a sentence. This is where a turn begins. */
    public void onTranscriptFinal(UUID callId, String text, long tSttFinal) {
        CallSession session = registry.get(callId);
        if (session == null) return;

        session.touch();
        if (text == null || text.isBlank()) {
            nothingHeard(session);
            return;
        }
        session.heardSomething();

        int turn = session.nextTurn();
        session.setBusy(true);
        session.remember(CALLER, text);
        callLog.record(callId, CallDtos.LineToStore.caller(text, session.language().code(),
                session.mode().name(), turn, tSttFinal));

        turnWorkers.submit(() -> new TurnRunner(this, session, turn, tSttFinal).run());
    }

    /** The voice server heard the reply start. Completes the turn's timing. */
    public void onSpoken(UUID callId, int turn, long tTtsFirst) {
        CallSession session = registry.get(callId);
        if (session == null) return;

        session.recordTtsFirst(turn, tTtsFirst);
        Integer messageSeq = session.agentLine(turn);
        if (messageSeq != null) callLog.stampTtsFirst(callId, messageSeq, turn, tTtsFirst);
    }

    public void onCallEnd(UUID callId, String reason) {
        registry.remove(callId);
        callLog.end(callId, reason);
    }

    /**
     * The socket carrying a call went away. The voice server is allowed a few
     * seconds to dial back in before the call is written off, because a link
     * that comes straight back is a blip and the caller is still on the line.
     */
    public void onLinkClosed(UUID callId, Object link) {
        CallSession session = registry.get(callId);
        if (session == null || !session.isCurrentLink(link)) return;

        linkGrace.schedule(() -> endIfStillGone(callId, link), LINK_GRACE_SECONDS, TimeUnit.SECONDS);
    }

    private void endIfStillGone(UUID callId, Object link) {
        CallSession session = registry.get(callId);
        if (session == null || !session.isCurrentLink(link)) return;

        log.info("[{}] the voice server did not come back", callId);
        onCallEnd(callId, "voice_link_lost");
    }

    // -------------------------------------------------------------- screening --

    /**
     * The operator overruling the agent from the panel.
     *
     * @return false when the call is not allowed to make that move; the same
     *         table refuses the model and the person, which is the point of it
     */
    public boolean onOperatorMode(UUID callId, CallMode wanted, String reason) {
        CallSession session = registry.get(callId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, Lang.ERR_CALL_NOT_LIVE);
        }
        if (modes.apply(session, wanted, reason == null || reason.isBlank()
                ? "changed by the operator" : reason) == null) {
            return false;
        }

        if (modes.endsTheCall(wanted)) {
            session.requestHangup(wanted.name().toLowerCase(),
                    spokenLine(session, modes.farewellKey(wanted)));
            // A turn in flight will send it once it stops talking.
            if (!session.isBusy()) hangUpIfAsked(session);
        }
        return true;
    }

    // --------------------------------------------------------------- silence --

    /** Asks whether anyone is still there, once, after the configured wait. */
    void warnAboutSilence(CallSession session) {
        log.info("[{}] nobody has said anything; asking", session.callId());
        sayOutsideATurn(session, spokenLine(session, "voice.still_there"));
    }

    void hangUpForSilence(CallSession session) {
        log.info("[{}] still nothing; ending the call", session.callId());
        session.requestHangup("inactivity", spokenLine(session, "voice.inactivity_farewell"));
        hangUpIfAsked(session);
    }

    /**
     * Two utterances in a row that the recogniser made nothing of is a caller
     * worth asking to repeat themselves — most often someone switching between
     * Bangla and English mid-sentence while the recogniser listens for one.
     */
    private void nothingHeard(CallSession session) {
        if (session.unheardInARow() < UNHEARD_BEFORE_REPROMPT) return;

        session.heardSomething();
        sayOutsideATurn(session, spokenLine(session, "voice.not_understood"));
    }

    // ---------------------------------------------------------- one turn --

    private void greet(CallSession session) {
        String greeting = greetingFor(session);
        session.remember(AGENT, greeting);
        callLog.record(session.callId(), agentLine(session, greeting));
        // last=false: the language question is coming, and the microphone stays
        // shut until the agent has finished asking it.
        session.send("greeting", "text", greeting,
                "language", session.language().code(), "last", false);
        askWhichLanguage(session);

        if (!router.isReady(session.selection()) && session.firstNotice()) {
            noticeNoModel(session);
        }
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
        String english = Lang.ui("en").get(key);
        String bangla = Lang.ui("bn").get(key);

        session.send("say", "seq", 0, "text", english, "language", "en", "last", false);
        session.send("say", "seq", 0, "text", bangla, "language", "bn", "last", true);

        String both = Lang.bilingual(key);
        session.remember(AGENT, both);
        callLog.record(session.callId(), agentLine(session, both));
    }

    /**
     * Sends the last thing the agent says in a turn, writes the whole reply
     * down, and remembers it. The final message always carries last=true, even
     * when there is nothing left to say, because that is how the voice server
     * knows the reply is over — and how the microphone is opened again.
     */
    void finishTurn(CallSession session, int turn, long tSttFinal,
                    String tail, String whole, Long tLlmFirst) {
        session.send("say", "seq", turn, "text", tail == null ? "" : tail,
                "language", session.language().code(), "last", true);
        if (whole != null && !whole.isBlank()) {
            session.remember(AGENT, whole);
            persistAgentLine(session, turn, whole, tSttFinal, tLlmFirst);
        }

        session.setBusy(false);
        session.touch();
        hangUpIfAsked(session);
    }

    private void persistAgentLine(CallSession session, int turn, String whole,
                                  long tSttFinal, Long tLlmFirst) {
        Long tTtsFirst = session.ttsFirst(turn);
        int messageSeq = callLog.record(session.callId(), CallDtos.LineToStore.agent(whole,
                session.language().code(), session.mode().name(), turn,
                tSttFinal, tLlmFirst, tTtsFirst));
        session.rememberAgentLine(turn, messageSeq);

        // The voice server may have reported the first audio byte while the
        // line was still being written; if so, fill it in now.
        Long late = session.ttsFirst(turn);
        if (tTtsFirst == null && late != null) {
            callLog.stampTtsFirst(session.callId(), messageSeq, turn, late);
        }
    }

    /**
     * Anything the agent says that no caller asked for — a re-prompt, a check
     * that someone is still there. It carries turn 0, so the panel does not
     * count it as an exchange and expects no reply time for it.
     */
    private void sayOutsideATurn(CallSession session, String text) {
        if (text == null || text.isBlank()) return;

        session.remember(AGENT, text);
        callLog.record(session.callId(), agentLine(session, text));
        session.send("say", "seq", 0, "text", text,
                "language", session.language().code(), "last", true);
    }

    private static CallDtos.LineToStore agentLine(CallSession session, String text) {
        return CallDtos.LineToStore.agent(text, session.language().code(),
                session.mode().name(), 0, null, null, null);
    }

    /**
     * Sends the hangup once, after whatever was being said has been said. The
     * farewell travels with it rather than as another sentence, so the voice
     * server can speak it and close in one move.
     */
    private void hangUpIfAsked(CallSession session) {
        CallSession.Hangup hangup = session.takeHangup();
        if (hangup == null) return;

        if (hangup.farewellText() != null && !hangup.farewellText().isBlank()) {
            session.remember(AGENT, hangup.farewellText());
            callLog.record(session.callId(), agentLine(session, hangup.farewellText()));
        }
        log.info("[{}] hanging up ({})", session.callId(), hangup.reason());
        session.send("hangup", "reason", hangup.reason(), "language", session.language().code(),
                "farewellText", hangup.farewellText() == null ? "" : hangup.farewellText());
    }

    // -------------------------------------------------- what TurnRunner needs --

    LlmRouter router() {
        return router;
    }

    ToolRegistry tools() {
        return tools;
    }

    ToolExecutor toolExecutor() {
        return toolExecutor;
    }

    void noticeNoModel(CallSession session) {
        callLog.notice(session.callId(), "livecall.no_model");
    }

    /**
     * Builds one request. Tool results ride in as a caller-side message: it is
     * the one role both vendors accept without a matching call id, and it is
     * deliberately not remembered — the history keeps what was said aloud, not
     * the machinery underneath it.
     */
    LlmRequest request(CallSession session, List<String> toolSchemas, String toolResults) {
        List<LlmRequest.Message> messages = session.messages();
        if (toolResults != null) {
            messages = new ArrayList<>(messages);
            messages.add(new LlmRequest.Message(CALLER, toolResults));
        }
        LlmRouter.Selection selection = session.selection();
        return new LlmRequest(selection.model(), prompts.build(session), messages,
                toolSchemas, selection.temperature());
    }

    /** One of the few lines the agent says that the model did not write. */
    String spokenLine(CallSession session, String stringKey) {
        return Lang.ui(session.language().code()).get(stringKey);
    }

    private String greetingFor(CallSession session) {
        AiSettings settings = session.aiSettings();
        boolean bangla = session.language() == Language.BN;
        String greeting = settings == null ? null
                : (bangla ? settings.getGreetingBn() : settings.getGreetingEn());
        if (greeting != null && !greeting.isBlank()) return greeting;

        String template = bangla ? Lang.DEFAULT_GREETING_BN : Lang.DEFAULT_GREETING_EN;
        return template.formatted(session.business().getName());
    }
}
