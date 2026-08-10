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
import com.ulab.agent.services.PostCallService;
import com.ulab.agent.utils.Lang;
import com.ulab.agent.utils.PiiMasker;
import com.ulab.agent.utils.SlangGuard;
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

    /** Exchanges one call may have before it is ended as a runaway. */
    private static final int MAX_TURNS_PER_CALL = 100;

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
    private final PostCallService postCall;
    private final Greeter greeter;
    private final CallInterventions interventions;

    private final ExecutorService turnWorkers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService linkGrace = Executors.newSingleThreadScheduledExecutor();

    public ConversationBrain(CallRegistry registry, PromptBuilder prompts, LlmRouter router,
                             CallLogService callLog, BusinessRepository businesses,
                             AiSettingsRepository aiSettings, CallModeMachine modes,
                             ToolRegistry tools, ToolExecutor toolExecutor,
                             ClientService clients, PostCallService postCall, Greeter greeter,
                             CallInterventions interventions) {
        this.clients = clients;
        this.postCall = postCall;
        this.greeter = greeter;
        this.interventions = interventions;
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

        if (!registry.hasRoom()) {
            log.warn("Refused call {}: {} are already running", callId, registry.count());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    Lang.ERR_TOO_MANY_CALLS);
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

    /**
     * The caller has opened their mouth. Nothing else is known about it yet —
     * this comes from the voice detector, not the recogniser — and that is
     * exactly why it is worth having: the free recogniser says nothing at all
     * until a sentence is finished, so without this the only sign of a caller
     * halfway through a long sentence is silence.
     */
    public void onCallerSpeaking(UUID callId) {
        CallSession session = registry.get(callId);
        if (session != null) session.callerStartedSpeaking();
    }

    public void onCallerStopped(UUID callId) {
        CallSession session = registry.get(callId);
        if (session != null) session.callerStoppedSpeaking();
    }

    /** The caller finished a sentence. This is where a turn begins. */
    public void onTranscriptFinal(UUID callId, String text, long tSttFinal) {
        CallSession session = registry.get(callId);
        if (session == null) return;

        session.callerStoppedSpeaking();
        if (text == null || text.isBlank()) {
            interventions.nothingHeard(session);
            return;
        }
        session.heardSomething();
        if (SlangGuard.isAbusive(text)) {
            interventions.answerAbuse(session, text);
            return;
        }
        followTheCallersLanguage(session, text);

        int turn = session.nextTurn();
        if (turn > MAX_TURNS_PER_CALL) {
            // A telephone conversation does not run to hundreds of exchanges.
            // Something that does is a loop, and every lap of it is a billed
            // request — so the call ends rather than the quota.
            log.warn("[{}] reached {} turns; ending the call", callId, MAX_TURNS_PER_CALL);
            session.requestHangup("turn_limit", spokenLine(session, "voice.goodbye"));
            interventions.hangUpIfAsked(session);
            return;
        }

        session.setBusy(true);
        rememberWhatWasSaid(session, turn, text, tSttFinal);

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

    /**
     * The transcript keeps what the caller actually said; the model is given a
     * masked copy of it.
     *
     * Those are two different audiences. The operator watching the panel is
     * supervising a call and has to see the ID number that was read out. The
     * model does not need it — it has the customer's record already — and
     * whatever goes into its history goes on to a vendor's servers, into the
     * written summary, and out again in an email.
     */
    private void rememberWhatWasSaid(CallSession session, int turn, String text, long tSttFinal) {
        String forTheModel = PiiMasker.mask(text);
        if (!forTheModel.equals(text)) {
            log.info("[{}] turn {} had personal details in it; the model was given the masked "
                    + "version", session.callId(), turn);
        }

        session.remember(CALLER, forTheModel);
        callLog.record(session.callId(), CallDtos.LineToStore.caller(text,
                session.language().code(), session.mode().name(), turn, tSttFinal));
    }

    /**
     * Ends the call, and — for the first end only — sets the write-up going.
     * The summary and any escalation email happen on their own thread, because
     * the caller has already gone and nothing is waiting on them.
     */
    /**
     * The agent has finished speaking and the caller can talk.
     *
     * This is where a caller's silence begins. It cannot be measured from the
     * moment a reply is sent — audio is handed to the transport in
     * milliseconds and then plays for several seconds — so the voice server,
     * which is the only part that knows how long its own audio lasts, says
     * when. Without it a caller is asked whether they are still there while
     * the greeting is still being read to them.
     */
    public void onAgentDone(UUID callId) {
        CallSession session = registry.get(callId);
        if (session != null) session.agentFinishedSpeaking();
    }

    public void onCallEnd(UUID callId, String reason) {
        registry.remove(callId);
        if (callLog.end(callId, reason)) postCall.onCallEnded(callId);
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
            if (!session.isBusy()) interventions.hangUpIfAsked(session);
        }
        return true;
    }

    // ------------------------------------------------------- following along --

    /**
     * Moves the call to whichever language the caller is actually using.
     *
     * The model has an action for this and is told to use it, but it does not
     * have to — and when it does not, the recogniser goes on listening for the
     * language nobody is speaking. What the caller said is already written in
     * one script or the other, and that is not a judgement call.
     */
    private void followTheCallersLanguage(CallSession session, String text) {
        Language wanted = LanguageSense.wantedBy(text, session.language());
        if (LanguageSense.apply(session, wanted, callLog)) {
            log.info("[{}] the caller is speaking {}; the call follows them",
                    session.callId(), wanted.code());
        }
    }

    // ---------------------------------------------------------- one turn --

    private void greet(CallSession session) {
        greeter.greet(session);
        if (!router.isReady(session.selection()) && session.firstNotice()) {
            noticeNoModel(session);
        }
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
        interventions.hangUpIfAsked(session);
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
        return CallInterventions.spokenLine(session, stringKey);
    }
}
