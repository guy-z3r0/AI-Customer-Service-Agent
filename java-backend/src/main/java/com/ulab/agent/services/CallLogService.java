package com.ulab.agent.services;

import com.ulab.agent.api.LiveEventSocket;
import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.CallMessage;
import com.ulab.agent.domain.CallRecord;
import com.ulab.agent.domain.ModeTransition;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.domain.enums.MessageRole;
import com.ulab.agent.domain.enums.Telephony;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.repo.CallMessageRepository;
import com.ulab.agent.repo.CallRecordRepository;
import com.ulab.agent.repo.ModeTransitionRepository;
import com.ulab.agent.utils.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the record of a call: when it started, every line spoken, the timing of
 * each turn, and how it ended. Each line is also pushed to the panel's live
 * feed as it lands, so the transcript on screen and the transcript in the
 * database are the same thing arriving twice.
 *
 * Partial lines — the live guess while someone is still speaking — are
 * broadcast but never stored. They are wrong as often as not, and a transcript
 * full of half-sentences would be worthless afterwards.
 *
 * The three timestamps on a turn do not arrive together. Java knows when the
 * model spoke its first word; the voice server knows when the caller's audio
 * ended and when the reply's first audio byte went out. Whichever of the two
 * halves lands second is what completes the reading.
 */
@Service
public class CallLogService {

    private static final Logger log = LoggerFactory.getLogger(CallLogService.class);

    private final CallRecordRepository calls;
    private final CallMessageRepository messages;
    private final ModeTransitionRepository transitions;
    private final BusinessRepository businesses;
    private final ClientService clients;
    private final LiveEventSocket liveEvents;

    public CallLogService(CallRecordRepository calls, CallMessageRepository messages,
                          ModeTransitionRepository transitions, BusinessRepository businesses,
                          ClientService clients, LiveEventSocket liveEvents) {
        this.calls = calls;
        this.messages = messages;
        this.transitions = transitions;
        this.businesses = businesses;
        this.clients = clients;
        this.liveEvents = liveEvents;
    }

    /** Opens a call against whichever business is currently active. */
    @Transactional
    public CallRecord start(CallDtos.StartRequest request) {
        Business business = businesses.findFirstByActiveTrue().orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, Lang.ERR_NO_ACTIVE_BUSINESS));

        CallRecord call = new CallRecord();
        call.setBusinessId(business.getId());
        call.setTelephony(telephonyOf(request.telephony()));
        call.setFinalLanguage(Language.of(request.language()));
        // A call opens knowing who it is with only when the operator said so,
        // by dialling as a customer from the panel. That is an authenticated
        // choice made by a person looking at the record.
        //
        // A telephone number is not that. Caller ID is trivially spoofed, so an
        // inbound number is a hint and nothing more: it is passed to the agent
        // to look up and confirm during the call, and it does not by itself
        // bind the call to somebody's record. Before this, ringing in with a
        // forged caller ID was enough to be greeted by a customer's name and
        // answered from their history.
        clients.byCode(business.getId(), request.clientCode())
                .ifPresent(client -> call.setClientId(client.id()));
        calls.save(call);

        log.info("Call {} started for {} over {}", call.getId(), business.getSlug(), call.getTelephony());
        liveEvents.broadcast("call_started", event(
                "callId", call.getId(),
                "business", business.getName(),
                "telephony", call.getTelephony().name(),
                "language", call.getFinalLanguage().name()));
        return call;
    }

    @Transactional(readOnly = true)
    public CallRecord require(UUID callId) {
        return calls.findById(callId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, Lang.ERR_CALL_NOT_FOUND));
    }

    /** A live guess at what is being said. Shown in the panel, never stored. */
    public void partial(UUID callId, String text) {
        liveEvents.broadcast("partial", event("callId", callId, "text", text == null ? "" : text));
    }

    /**
     * Writes one finished line down and shows it in the panel.
     *
     * @return the sequence number it was stored under
     */
    @Transactional
    public int record(UUID callId, CallDtos.LineToStore line) {
        int seq = nextSeq(callId);
        messages.save(buildMessage(callId, seq, line));

        liveEvents.broadcast("line", event("callId", callId, "seq", seq,
                "turnSeq", line.turnSeq(), "role", roleOf(line.role()).name(),
                "text", line.text(), "language", line.language(), "mode", line.mode()));
        broadcastLatency(callId, line.turnSeq(), line.tSttFinal(), line.tLlmFirst(), line.tTtsFirst());
        return seq;
    }

    /**
     * Fills in when a turn's reply started being heard. This arrives from the
     * voice server and can overtake the line it belongs to, which is why the
     * brain also passes the stamp in when it already has it.
     */
    @Transactional
    public void stampTtsFirst(UUID callId, int messageSeq, int turnSeq, long epochMillis) {
        CallMessage message = messages.findByCallIdAndSeq(callId, messageSeq).orElse(null);
        if (message == null || message.getTTtsFirst() != null) return;

        message.setTTtsFirst(Instant.ofEpochMilli(epochMillis));
        messages.save(message);
        broadcastLatency(callId, turnSeq, millisOf(message.getTSttFinal()),
                millisOf(message.getTLlmFirst()), epochMillis);
    }

    /** Something the operator should know about, shown as a toast in the panel. */
    public void notice(UUID callId, String stringKey) {
        liveEvents.broadcast("notice", event("callId", callId, "key", stringKey));
    }

    /**
     * Writes down a change of screening mode and shows it in the panel.
     *
     * The call's own row keeps the latest mode as well, so the history page can
     * say how a call was classified without replaying its transitions.
     */
    @Transactional
    public void recordModeChange(ModeTransition transition) {
        transitions.save(transition);
        calls.findById(transition.getCallId()).ifPresent(call -> {
            call.setFinalMode(transition.getToMode());
            calls.save(call);
        });

        liveEvents.broadcast("mode_change", event("callId", transition.getCallId(),
                "fromMode", transition.getFromMode() == null ? null : transition.getFromMode().name(),
                "toMode", transition.getToMode().name(),
                "reason", transition.getReason()));
    }

    /**
     * Ties a call to the customer it turned out to be. The name travels with the
     * event so the panel can say who is on the phone without a second request.
     *
     * @param recognised whether the caller was matched to a record that already
     *                   existed. The panel says which, because "Recognised:
     *                   Sadman" over a record written moments ago during this
     *                   same call reads as the app knowing who is on the phone
     *                   when all it did was write down what it was told.
     */
    @Transactional
    public void recordClient(UUID callId, UUID clientId, String name, boolean recognised) {
        calls.findById(callId).ifPresent(call -> {
            call.setClientId(clientId);
            calls.save(call);
        });

        log.info("Call {} is with client {} ({})", callId, clientId,
                recognised ? "recognised" : "newly written down");
        liveEvents.broadcast("client_identified", event("callId", callId,
                "clientId", clientId, "name", name, "recognised", recognised));
    }

    @Transactional
    public void recordLanguageChange(UUID callId, Language language) {
        calls.findById(callId).ifPresent(call -> {
            call.setFinalLanguage(language);
            calls.save(call);
        });

        log.info("Call {} switched to {}", callId, language);
        liveEvents.broadcast("language_change", event("callId", callId,
                "language", language.name()));
    }

    /**
     * Closes the call.
     *
     * Both the panel's hang-up button and the voice server report the end, so
     * this is often called twice for one call. That is not an error, but only
     * the first of them is the moment the call ended — and only that one may
     * set the write-up and the escalation email going.
     *
     * @return true when this call is what closed the record
     */
    @Transactional
    public boolean end(UUID callId, String reason) {
        CallRecord call = require(callId);
        if (call.getEndedAt() != null) return false;

        call.setEndedAt(Instant.now());
        call.setTerminationReason(reason == null || reason.isBlank() ? "hangup" : reason);
        calls.save(call);

        log.info("Call {} ended ({})", callId, call.getTerminationReason());
        liveEvents.broadcast("call_ended", event("callId", callId,
                "reason", call.getTerminationReason()));
        return true;
    }

    // ------------------------------------------------------------ internals --

    private CallMessage buildMessage(UUID callId, int seq, CallDtos.LineToStore line) {
        CallMessage message = new CallMessage();
        message.setCallId(callId);
        message.setSeq(seq);
        message.setRole(roleOf(line.role()));
        message.setText(line.text() == null ? "" : line.text());
        message.setLanguage(Language.of(line.language()));
        message.setModeAtTime(modeOf(line.mode()));
        message.setTSttFinal(instantOf(line.tSttFinal()));
        message.setTLlmFirst(instantOf(line.tLlmFirst()));
        message.setTTtsFirst(instantOf(line.tTtsFirst()));
        return message;
    }

    /**
     * The panel shows how long a turn took, broken into its stages, so a slow
     * reply can be blamed on the right one. It is only a reading once both ends
     * of the window are known.
     */
    private void broadcastLatency(UUID callId, int turnSeq, Long tSttFinal,
                                  Long tLlmFirst, Long tTtsFirst) {
        if (tSttFinal == null || tTtsFirst == null) return;

        Map<String, Object> reading = event("callId", callId, "turnSeq", turnSeq,
                "totalMs", tTtsFirst - tSttFinal);
        if (tLlmFirst != null) {
            reading.put("llmMs", tLlmFirst - tSttFinal);
            reading.put("ttsMs", tTtsFirst - tLlmFirst);
        }
        liveEvents.broadcast("latency", reading);
    }

    /**
     * The next free line number for a call.
     *
     * The call's own row is taken first, so two turns arriving together cannot
     * both read the same highest number and both add one to it. Without the
     * lock the unique constraint on (call_id, seq) caught the collision, but it
     * caught it by failing the insert — one line of the transcript lost, and an
     * exception in the middle of a live call.
     */
    private int nextSeq(UUID callId) {
        calls.lockForNextSeq(callId);
        return messages.highestSeq(callId) + 1;
    }

    private static Telephony telephonyOf(String raw) {
        return "twilio".equalsIgnoreCase(raw) ? Telephony.TWILIO : Telephony.BROWSER;
    }

    private static MessageRole roleOf(String raw) {
        if ("agent".equalsIgnoreCase(raw)) return MessageRole.AGENT;
        if ("caller".equalsIgnoreCase(raw)) return MessageRole.CALLER;
        return MessageRole.SYSTEM;
    }

    private static CallMode modeOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CallMode.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instantOf(Long epochMillis) {
        return epochMillis == null || epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
    }

    private static Long millisOf(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    /** Builds an event body from alternating key and value arguments. */
    private static Map<String, Object> event(Object... keysAndValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            body.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return body;
    }
}
