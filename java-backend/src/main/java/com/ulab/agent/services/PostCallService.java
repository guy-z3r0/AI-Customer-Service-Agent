package com.ulab.agent.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.brain.llm.LlmRequest;
import com.ulab.agent.brain.llm.LlmRouter;
import com.ulab.agent.brain.llm.LlmStreamHandler;
import com.ulab.agent.domain.CallSummary;
import com.ulab.agent.domain.EscalationContact;
import com.ulab.agent.repo.AiSettingsRepository;
import com.ulab.agent.repo.CallRecordRepository;
import com.ulab.agent.repo.CallSummaryRepository;
import com.ulab.agent.repo.EscalationContactRepository;
import com.ulab.agent.utils.Lang;
import com.ulab.agent.utils.PiiMasker;
import com.ulab.agent.utils.Prompts;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * What happens after everyone has hung up: the call is read back, written up,
 * and — if it was the kind of call a person has to take over — emailed to that
 * person.
 *
 * All of it runs on its own thread. The caller is gone and nobody is waiting,
 * but the websocket that carried the call is still closing and a summary that
 * takes two seconds to write must not hold it open.
 *
 * Everything sent to the model or into the email goes through
 * {@link PiiMasker} first. The transcript kept in the database is the real one,
 * because the operator is meant to see what was said; what leaves the building
 * is the masked one.
 */
@Service
public class PostCallService {

    private static final Logger log = LoggerFactory.getLogger(PostCallService.class);

    /** Summaries are a report, not a conversation: as little invention as possible. */
    private static final double SUMMARY_TEMPERATURE = 0.2;

    /** The one mode that means a person has been promised. */
    private static final String NEEDS_A_PERSON = "COMPLEX_REQUEST";

    /**
     * How many calls may be written up at once.
     *
     * Each write-up is a model request that is paid for, and the executor below
     * will happily start one per call that ends. Four is enough that a summary
     * never waits long behind another, and few enough that a burst of endings
     * cannot open an unbounded number of billed requests at the same moment.
     */
    private static final int CONCURRENT_WRITE_UPS = 4;

    /** How long a shutdown waits for a summary that is already being written. */
    private static final int DRAIN_SECONDS = 30;

    private final CallHistoryService history;
    private final CallRecordRepository calls;
    private final CallSummaryRepository summaries;
    private final AiSettingsRepository aiSettings;
    private final EscalationContactRepository contacts;
    private final LlmRouter router;
    private final MailService mail;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore modelRequests = new Semaphore(CONCURRENT_WRITE_UPS);

    public PostCallService(CallHistoryService history, CallRecordRepository calls,
                           CallSummaryRepository summaries, AiSettingsRepository aiSettings,
                           EscalationContactRepository contacts, LlmRouter router,
                           MailService mail) {
        this.history = history;
        this.calls = calls;
        this.summaries = summaries;
        this.aiSettings = aiSettings;
        this.contacts = contacts;
        this.router = router;
        this.mail = mail;
    }

    /**
     * Lets the work already in flight finish before the process goes away.
     *
     * shutdown() on its own only stops new work being accepted; it returns
     * immediately and the JVM exits underneath whatever was running. A summary
     * being written at that moment was simply lost, and if it was the kind of
     * call that ends in an email to a colleague, so was the email.
     */
    @PreDestroy
    void stopWorkers() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Gave up waiting for the last write-up after {}s", DRAIN_SECONDS);
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Hands the call over to be written up. Returns at once. */
    public void onCallEnded(UUID callId) {
        workers.submit(() -> {
            try {
                modelRequests.acquire();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                writeUp(callId);
            } catch (RuntimeException failed) {
                // A call that cannot be summarised is still a call that happened;
                // the transcript is safe either way.
                log.warn("[{}] could not be written up: {}", callId, failed.toString());
            } finally {
                modelRequests.release();
            }
        });
    }

    // --------------------------------------------------------- the write-up --

    void writeUp(UUID callId) {
        CallDtos.CallDetail detail = history.detail(callId);
        if (detail.lines().isEmpty()) {
            log.info("[{}] nothing was said, so there is nothing to summarise", callId);
            return;
        }
        if (summaries.existsById(callId)) return;  // already written up

        String language = languageOf(detail);
        String transcript = maskedTranscript(detail);
        Written written = compose(callId, transcript, language);
        persist(callId, written, detail);
        escalateIfPromised(detail, written, transcript);
    }

    /** What the model wrote, or what was written for it when it was unreachable. */
    record Written(String text, JsonObject structured, JsonArray actions) {
    }

    private Written compose(UUID callId, String transcript, String language) {
        LlmRouter.Selection selection = router.select(aiSettings.findById(businessOf(callId))
                .orElse(null));
        if (!router.isReady(selection)) {
            log.info("[{}] no model key, so the summary says so", callId);
            return unwritten(callId, language);
        }

        try {
            Written parsed = parse(ask(selection, transcript, language));
            return parsed == null ? unwritten(callId, language) : parsed;
        } catch (RuntimeException failed) {
            log.warn("[{}] the summary request failed: {}", callId, failed.toString());
            return unwritten(callId, language);
        }
    }

    /**
     * Puts the transcript to the model and waits for the whole reply.
     *
     * The streaming interface is used the unstreamed way on purpose: there is
     * nobody on the line to hear it arrive early, and a summary is only useful
     * once it is complete enough to parse.
     */
    private String ask(LlmRouter.Selection selection, String transcript, String language) {
        String directive = "bn".equals(language) ? Prompts.ANSWER_IN_BN : Prompts.ANSWER_IN_EN;
        LlmRequest request = new LlmRequest(selection.model(),
                Prompts.SUMMARY_ROLE + " " + directive,
                List.of(new LlmRequest.Message("user",
                        Prompts.TRANSCRIPT + "\n" + transcript + "\n\n" + Prompts.SUMMARY_REQUEST)),
                List.of(), SUMMARY_TEMPERATURE);

        Reply reply = new Reply();
        router.provider(selection).chatStream(request, reply);
        if (reply.failure != null) throw new IllegalStateException(reply.failure);
        return reply.text.toString();
    }

    /** Collects a whole reply, because there is nobody left to speak it to. */
    private static final class Reply implements LlmStreamHandler {

        private final StringBuilder text = new StringBuilder();
        private Throwable failure;

        @Override
        public void onTextDelta(String delta) {
            text.append(delta);
        }

        @Override
        public void onToolCall(String name, String argumentsJson) {
            log.debug("The summariser asked for {}, which it was not offered", name);
        }

        @Override
        public void onDone() {
            // The reply is read once chatStream has returned.
        }

        @Override
        public void onError(Throwable error) {
            failure = error;
        }
    }

    /**
     * Reads the model's JSON, forgiving the two things models do to it: wrapping
     * it in a code fence, and saying a sentence before it starts.
     *
     * @return null when there is no readable JSON object in the reply at all
     */
    static Written parse(String reply) {
        if (reply == null || reply.isBlank()) return null;

        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return null;

        try {
            JsonElement parsed = JsonParser.parseString(reply.substring(start, end + 1));
            if (!parsed.isJsonObject()) return null;

            JsonObject body = parsed.getAsJsonObject();
            String text = textOf(body, "summary_text");
            if (text.isBlank()) return null;

            return new Written(text, objectOf(body, "structured"), arrayOf(body, "action_items"));
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static Written unwritten(UUID callId, String language) {
        log.info("[{}] summarised by the system rather than by a model", callId);
        return new Written(Lang.ui(language).get("summary.unwritten"),
                new JsonObject(), new JsonArray());
    }

    // ------------------------------------------------------------ persisting --

    /**
     * Saves the write-up, with the call's own screening history written into it.
     *
     * The path a call took through the four modes is a fact this app already
     * knows, so it is filled in from the transitions rather than left to the
     * model, which would only be guessing at it from the words.
     */
    private void persist(UUID callId, Written written, CallDtos.CallDetail detail) {
        JsonObject structured = written.structured();
        structured.addProperty("mode_path", modePath(detail));

        CallSummary summary = new CallSummary();
        summary.setCallId(callId);
        summary.setSummaryText(written.text());
        summary.setStructuredJson(structured.toString());
        summary.setActionItemsJson(written.actions().toString());
        summaries.save(summary);

        log.info("[{}] written up: {}", callId, written.text());
    }

    private static String modePath(CallDtos.CallDetail detail) {
        return detail.transitions().stream().map(CallDtos.TransitionView::toMode)
                .reduce((first, second) -> first + " -> " + second).orElse("");
    }

    // ----------------------------------------------------------- escalation --

    /**
     * Emails the colleague who has to pick this up.
     *
     * A call in any other mode is written up and left alone: an email for every
     * call is an email nobody reads, and the promise of a person was only made
     * on this one.
     */
    private void escalateIfPromised(CallDtos.CallDetail detail, Written written, String transcript) {
        if (!NEEDS_A_PERSON.equals(detail.call().mode())) return;

        UUID businessId = businessOf(detail.call().id());
        List<EscalationContact> people = contacts.findByBusinessIdOrderByPriorityAsc(businessId);
        Map<String, String> t = Lang.ui(languageOf(detail));

        boolean sent = mail.send(people.stream().map(EscalationContact::getEmail).toList(),
                t.get("email.escalation_subject").formatted(detail.call().business()),
                escalationBody(detail, written, transcript, t));
        log.info("[{}] needs a person; {} contact(s), email {}", detail.call().id(),
                people.size(), sent ? "sent" : "logged only");
    }

    private static String escalationBody(CallDtos.CallDetail detail, Written written,
                                         String transcript, Map<String, String> t) {
        CallDtos.CallListItem call = detail.call();
        StringBuilder body = new StringBuilder(t.get("email.escalation_intro")).append("\n\n");
        body.append(t.get("email.when")).append(": ").append(call.startedAt()).append('\n');
        body.append(t.get("email.caller")).append(": ")
                .append(call.caller() == null ? t.get("call.not_recognised") : call.caller())
                .append('\n');
        body.append(t.get("email.reason")).append(": ").append(reasonFor(detail)).append("\n\n");

        body.append(t.get("email.summary")).append('\n').append(written.text()).append("\n\n");
        if (!written.actions().isEmpty()) {
            body.append(t.get("email.actions")).append('\n');
            written.actions().forEach(item -> body.append("- ")
                    .append(item.isJsonPrimitive() ? item.getAsString() : item.toString())
                    .append('\n'));
            body.append('\n');
        }

        body.append(t.get("email.transcript")).append('\n').append(transcript).append('\n');
        body.append(t.get("email.footer"));
        return body.toString();
    }

    /** Why the agent handed this over — the reason it gave when it did. */
    private static String reasonFor(CallDtos.CallDetail detail) {
        return detail.transitions().stream()
                .filter(transition -> NEEDS_A_PERSON.equals(transition.toMode()))
                .map(CallDtos.TransitionView::reason)
                .reduce((first, second) -> second)
                .orElse("");
    }

    // ------------------------------------------------------------ internals --

    /** The call as the model and the email see it: masked, one line per speaker. */
    private static String maskedTranscript(CallDtos.CallDetail detail) {
        Map<String, String> t = Lang.ui(languageOf(detail));
        StringBuilder text = new StringBuilder();
        for (CallDtos.TranscriptLine line : detail.lines()) {
            text.append(t.getOrDefault("livecall.role_" + line.role().toLowerCase(), line.role()))
                    .append(": ").append(PiiMasker.mask(line.text())).append('\n');
        }
        return text.toString().strip();
    }

    private static String languageOf(CallDtos.CallDetail detail) {
        String language = detail.call().language();
        return language == null ? "en" : language.toLowerCase();
    }

    private UUID businessOf(UUID callId) {
        return calls.findById(callId).orElseThrow(
                () -> new IllegalStateException("Call " + callId + " is gone")).getBusinessId();
    }

    private static String textOf(JsonObject body, String key) {
        JsonElement value = body.get(key);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString().strip();
    }

    private static JsonObject objectOf(JsonObject body, String key) {
        JsonElement value = body.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arrayOf(JsonObject body, String key) {
        JsonElement value = body.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
}
