package com.ulab.agent.brain;

import com.ulab.agent.brain.llm.LlmStreamHandler;
import com.ulab.agent.utils.Lang;
import com.ulab.agent.utils.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One turn: ask the model, speak each sentence as it closes, carry out whatever
 * it asked for, and give it one more pass to say something about the result.
 *
 * The second pass is offered no tools at all. That is what stops a model asking
 * for the same thing over and over: with nothing to reach for it has to answer
 * in words, so a turn can go around at most twice however hard it tries.
 */
final class TurnRunner implements LlmStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(TurnRunner.class);

    private final ConversationBrain brain;
    private final CallSession session;
    private final int turn;
    private final long tSttFinal;

    private final SentenceSplitter splitter = new SentenceSplitter();
    private final StringBuilder whole = new StringBuilder();
    private final List<ToolCall> asked = new ArrayList<>();

    private Long tLlmFirst;
    private String tail = "";
    private boolean failed;

    TurnRunner(ConversationBrain brain, CallSession session, int turn, long tSttFinal) {
        this.brain = brain;
        this.session = session;
        this.turn = turn;
        this.tSttFinal = tSttFinal;
    }

    /** @param arguments the tool's arguments as JSON text, exactly as the model wrote them */
    private record ToolCall(String name, String arguments) {
    }

    void run() {
        if (!brain.router().isReady(session.selection())) {
            String apology = brain.spokenLine(session, "voice.no_model");
            if (session.firstNotice()) brain.noticeNoModel(session);
            brain.finishTurn(session, turn, tSttFinal, apology, apology, null);
            return;
        }

        ask(brain.tools().schemas(), null);
        if (!asked.isEmpty()) doWhatWasAsked();
        if (failed) tail = brain.spokenLine(session, "voice.trouble");

        brain.finishTurn(session, turn, tSttFinal, tail, whole.append(tail).toString().strip(),
                tLlmFirst);
    }

    // -------------------------------------------------------------- the model --

    private void ask(List<String> toolSchemas, String toolResults) {
        failed = false;
        try {
            brain.router().provider(session.selection())
                    .chatStream(brain.request(session, toolSchemas, toolResults), this);
        } catch (RuntimeException e) {
            onError(e);
        }
    }

    /**
     * Runs the actions, then gives the model the last word — unless one of them
     * ended the call, in which case the farewell is the last word and there is
     * nothing to add to it.
     */
    private void doWhatWasAsked() {
        StringBuilder results = new StringBuilder(Prompts.TOOL_RESULTS).append('\n');
        for (ToolCall call : asked) {
            results.append(call.name()).append(" -> ")
                    .append(brain.toolExecutor().run(session, call.name(), call.arguments()))
                    .append('\n');
        }
        asked.clear();
        if (session.isEnding()) return;

        // Anything the model said before reaching for a tool is still owed to
        // the caller, and it goes out before the second pass adds to it.
        flushTail();
        ask(List.of(), results.toString());
    }

    private void flushTail() {
        if (tail.isBlank()) return;
        speak(tail);
        tail = "";
    }

    private void speak(String sentence) {
        whole.append(sentence).append(' ');
        session.send("say", "seq", turn, "text", sentence,
                "language", session.language().code(), "last", false);
    }

    // ----------------------------------------------------- stream callbacks --

    @Override
    public void onTextDelta(String text) {
        if (tLlmFirst == null) tLlmFirst = Instant.now().toEpochMilli();
        splitter.push(text).forEach(this::speak);
    }

    @Override
    public void onToolCall(String name, String argumentsJson) {
        asked.add(new ToolCall(name, argumentsJson));
    }

    @Override
    public void onDone() {
        tail = splitter.drain();
    }

    @Override
    public void onError(Throwable error) {
        failed = true;
        log.warn("[{}] turn {} failed: {}", session.callId(), turn, error.toString());
    }
}
