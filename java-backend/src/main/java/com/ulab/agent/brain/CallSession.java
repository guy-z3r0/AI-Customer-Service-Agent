package com.ulab.agent.brain;

import com.ulab.agent.api.dto.ClientDtos;
import com.ulab.agent.brain.llm.LlmRequest;
import com.ulab.agent.brain.llm.LlmRouter;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.domain.enums.Telephony;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One live call, as the brain sees it.
 *
 * The business, its persona and its knowledge are read once when the call
 * starts and held here for its whole length. That is deliberate: a caller is
 * waiting on every turn, and the knowledge cannot change under them mid-call
 * anyway. Editing a business in the panel affects the next call, not this one.
 *
 * The session is touched from two threads — the websocket that carries the
 * call and the worker that runs a turn — so everything that moves is either
 * atomic or synchronised.
 */
public class CallSession {

    private final UUID callId;
    private final Business business;
    private final AiSettings aiSettings;

    /** The knowledge base already written out as prompt text. */
    private final String knowledge;

    private final LlmRouter.Selection selection;
    private final Telephony telephony;

    /**
     * How to reach the voice server carrying this call, and which of its
     * sockets that is. Both change together when the voice server drops its
     * connection and dials back in mid-call.
     */
    private volatile Consumer<Map<String, Object>> outbound;
    private volatile Object link;

    private volatile Language language;
    private volatile CallMode mode = CallMode.NEW_CUSTOMER;

    /** Who is calling, once anything has worked that out. Null for a stranger. */
    private volatile ClientDtos.ClientView client;

    /** Set once the call is on its way out; the farewell is spoken first. */
    private volatile Hangup hangup;

    /** When the call last did anything, which is what the watchdog watches. */
    private volatile Instant lastActivity = Instant.now();
    private volatile boolean warnedAboutSilence;
    private volatile boolean busy;

    /**
     * When the caller started the sentence they are in the middle of, or null
     * when the line is theirs and quiet. A caller reading an address out is not
     * a silent one, and asking them whether they are still there while they do
     * it is the rudest thing this app can do.
     */
    private volatile Instant speakingSince;

    /**
     * True while the agent's own audio is still playing. It holds the floor
     * from the moment a line is sent until the voice server reports the sound
     * has finished, which on a greeting is a dozen seconds.
     */
    private volatile boolean agentHasTheFloor;

    /** Wrong guesses at who is calling before this call may not ask again. */
    private static final int MAX_LOOKUP_ATTEMPTS = 3;

    /**
     * How long a caller may hold the floor before the watchdog stops believing
     * them. The flag is cleared when their sentence ends, and this is the
     * backstop for the sentence that never does — a recogniser that dies
     * mid-utterance would otherwise switch the silence watch off for good.
     */
    private static final int LONGEST_CREDIBLE_SENTENCE_S = 60;

    private final AtomicInteger failedLookups = new AtomicInteger();
    private final AtomicInteger unheard = new AtomicInteger();
    private final AtomicInteger slangStrikes = new AtomicInteger();
    private final AtomicBoolean hangupSent = new AtomicBoolean();

    private final Deque<LlmRequest.Message> history = new ArrayDeque<>();
    private final Map<Integer, Long> ttsFirstByTurn = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> agentLineByTurn = new ConcurrentHashMap<>();
    private final AtomicInteger turns = new AtomicInteger();
    private final AtomicBoolean noticed = new AtomicBoolean();

    public CallSession(UUID callId, Business business, AiSettings aiSettings, String knowledge,
                       LlmRouter.Selection selection, Telephony telephony, Language language,
                       Object link, Consumer<Map<String, Object>> outbound) {
        this.callId = callId;
        this.business = business;
        this.aiSettings = aiSettings;
        this.knowledge = knowledge;
        this.selection = selection;
        this.telephony = telephony;
        this.language = language;
        this.link = link;
        this.outbound = outbound;
    }

    // ---------------------------------------------------------------- state --

    public UUID callId() { return callId; }

    public Business business() { return business; }

    public AiSettings aiSettings() { return aiSettings; }

    public String knowledge() { return knowledge; }

    public LlmRouter.Selection selection() { return selection; }

    public Telephony telephony() { return telephony; }

    public Language language() { return language; }

    public void setLanguage(Language language) { this.language = language; }

    public CallMode mode() { return mode; }

    public void setMode(CallMode mode) { this.mode = mode; }

    public ClientDtos.ClientView client() { return client; }

    public void setClient(ClientDtos.ClientView client) { this.client = client; }

    public int nextTurn() { return turns.incrementAndGet(); }

    /** How many exchanges this call has had so far. The model is told, so it
     * can tell a caller with a question from one who is only playing. */
    public int turnsSoFar() { return turns.get(); }

    /** True the first time it is asked, so a warning is spoken once per call. */
    public boolean firstNotice() { return noticed.compareAndSet(false, true); }

    /**
     * How many times this call has failed to identify the caller.
     *
     * Customer codes run C001, C002, C003, so a line that answers an unlimited
     * number of guesses is a way of reading the customer list one code at a
     * time. Three wrong answers and this call stops being able to ask.
     */
    public int recordFailedLookup() { return failedLookups.incrementAndGet(); }

    public boolean lookupsExhausted() { return failedLookups.get() >= MAX_LOOKUP_ATTEMPTS; }

    // ---------------------------------------------------- ending the call --

    /**
     * Asks for the call to end once the agent has finished the sentence it is
     * on. Nothing hangs up mid-word: the farewell goes out after whatever the
     * turn was already saying, and the first request wins so two ways of
     * ending at once cannot both speak.
     */
    public synchronized void requestHangup(String reason, String farewellText) {
        if (hangup == null) hangup = new Hangup(reason, farewellText);
    }

    /** True once anything has asked for this call to end. */
    public boolean isEnding() { return hangup != null; }

    /** The hangup, once. Two things ending a call at once must not both speak. */
    public Hangup takeHangup() {
        return hangup != null && hangupSent.compareAndSet(false, true) ? hangup : null;
    }

    /** @param farewellText the last thing the caller hears, from Lang */
    public record Hangup(String reason, String farewellText) {
    }

    // ------------------------------------------------------------ silence --

    /** Anything at all happening on the call resets the silence clock. */
    public void touch() {
        lastActivity = Instant.now();
        warnedAboutSilence = false;
    }

    public Instant lastActivity() { return lastActivity; }

    /** True the first time silence goes past the warning threshold. */
    public boolean firstSilenceWarning() {
        if (warnedAboutSilence) return false;
        warnedAboutSilence = true;
        return true;
    }

    /** True while a turn is being answered, when silence means the model is thinking. */
    public boolean isBusy() { return busy; }

    public void setBusy(boolean busy) { this.busy = busy; }

    /** The caller has begun a sentence. Nothing may interrupt them until it ends. */
    public void callerStartedSpeaking() {
        speakingSince = Instant.now();
        touch();
    }

    public void callerStoppedSpeaking() {
        speakingSince = null;
        touch();
    }

    /** True while the caller is mid-sentence, and has not been for too long. */
    public boolean callerIsSpeaking() {
        Instant since = speakingSince;
        return since != null
                && Duration.between(since, Instant.now()).getSeconds() < LONGEST_CREDIBLE_SENTENCE_S;
    }

    public boolean agentHasTheFloor() { return agentHasTheFloor; }

    /**
     * The agent has stopped speaking and its audio has finished playing. The
     * caller's silence is measured from here, not from when the reply was sent.
     */
    public void agentFinishedSpeaking() {
        agentHasTheFloor = false;
        touch();
    }

    /**
     * Counts utterances the recogniser could make nothing of, in a row. One is
     * a cough; two in a row is a caller who needs to be asked to repeat
     * themselves, which is most of what Banglish sounds like to a recogniser
     * listening in one language. It keeps counting past that, because a line
     * that only ever produces noise has to end rather than ask for ever.
     */
    public int unheardInARow() { return unheard.incrementAndGet(); }

    public void heardSomething() { unheard.set(0); }

    /**
     * How many times this caller has sworn at the agent. The first is answered
     * with a warning and the second ends the call.
     */
    public int recordSlang() { return slangStrikes.incrementAndGet(); }

    // -------------------------------------------------------------- history --

    /** @param role "user" for the caller, "assistant" for the agent */
    public synchronized void remember(String role, String text) {
        history.addLast(new LlmRequest.Message(role, text));
        int limit = Math.max(2, maxHistoryTurns() * 2);
        while (history.size() > limit) {
            history.removeFirst();
        }
    }

    public synchronized List<LlmRequest.Message> messages() {
        return new ArrayList<>(history);
    }

    private int maxHistoryTurns() {
        return aiSettings == null ? 20 : aiSettings.getMaxHistoryTurns();
    }

    // ------------------------------------------------------------- timings --

    /**
     * The voice server reports when a turn's first audio left for the caller.
     * It can arrive either side of the agent's line being written down, so both
     * halves check for the other.
     */
    public void recordTtsFirst(int turn, long epochMillis) {
        ttsFirstByTurn.put(turn, epochMillis);
    }

    public Long ttsFirst(int turn) { return ttsFirstByTurn.get(turn); }

    public void rememberAgentLine(int turn, int messageSeq) {
        agentLineByTurn.put(turn, messageSeq);
    }

    public Integer agentLine(int turn) { return agentLineByTurn.get(turn); }

    // ------------------------------------------------------------- outbound --

    /** Points the call at a new socket after the voice server reconnected. */
    public void attach(Object link, Consumer<Map<String, Object>> outbound) {
        this.link = link;
        this.outbound = outbound;
    }

    /** False once a newer socket has taken this call over. */
    public boolean isCurrentLink(Object candidate) {
        return link == candidate;
    }

    /**
     * Sends one message down the call's websocket to the voice server.
     *
     * Anything the agent says takes the floor here rather than at each of the
     * five call sites that say something. One place cannot be forgotten; five
     * can, and the one that was forgotten is the one where the watchdog talks
     * over the caller.
     */
    public void send(String type, Object... keysAndValues) {
        if ("say".equals(type) || "greeting".equals(type)) agentHasTheFloor = true;

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            message.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        outbound.accept(message);
    }
}
