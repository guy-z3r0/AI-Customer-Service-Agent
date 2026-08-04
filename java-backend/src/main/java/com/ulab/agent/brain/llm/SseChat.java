package com.ulab.agent.brain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The half of a provider that is the same for every vendor: send the request,
 * read a server-sent-event stream back, hand each data line to whoever knows
 * how to read it.
 *
 * Both vendors speak the same transport and disagree only about the JSON
 * inside it, so this is where the connection, the timeout and the retry live,
 * and the provider classes are left holding nothing but their wire format.
 */
final class SseChat {

    private static final Logger log = LoggerFactory.getLogger(SseChat.class);

    /** How long to wait for the model to start answering. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String DATA_PREFIX = "data:";
    private static final String END_MARKER = "[DONE]";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private SseChat() {
    }

    /** Reads one data line of the stream and reports whatever it holds. */
    interface ChunkReader {
        void read(String json, LlmStreamHandler out);
    }

    static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json");
    }

    /**
     * Runs the exchange, retrying once if it failed before the model had said
     * anything. A stream that dies half way through a sentence is not retried:
     * the caller has already heard the first half and would hear it twice.
     */
    static void stream(String label, Supplier<HttpRequest> factory,
                       LlmStreamHandler handler, ChunkReader reader) {
        Counting out = new Counting(handler);
        try {
            run(factory.get(), out, reader);
        } catch (Exception first) {
            if (out.spoke || first instanceof RejectedByProvider) {
                handler.onError(first);
                return;
            }
            log.warn("{} stream failed before it said anything ({}) — retrying once",
                    label, first.toString());
            try {
                run(factory.get(), out, reader);
            } catch (Exception second) {
                handler.onError(second);
                return;
            }
        }
        handler.onDone();
    }

    private static void run(HttpRequest request, LlmStreamHandler out, ChunkReader reader)
            throws IOException, InterruptedException {
        HttpResponse<Stream<String>> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() >= 400) {
            throw new RejectedByProvider(response.statusCode(), firstLines(response.body()));
        }
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> readLine(line, out, reader));
        }
    }

    private static void readLine(String line, LlmStreamHandler out, ChunkReader reader) {
        if (!line.startsWith(DATA_PREFIX)) return;  // comments, event names, blanks

        String payload = line.substring(DATA_PREFIX.length()).trim();
        if (payload.isEmpty() || payload.equals(END_MARKER)) return;
        reader.read(payload, out);
    }

    private static String firstLines(Stream<String> body) {
        String detail = body.limit(4).reduce("", (a, b) -> a + " " + b).trim();
        return detail.length() > 300 ? detail.substring(0, 300) : detail;
    }

    /** An answer the vendor refused. Retrying a rejected key just fails twice. */
    static final class RejectedByProvider extends IOException {
        RejectedByProvider(int status, String detail) {
            super("the model service answered HTTP " + status + ": " + detail);
        }
    }

    /**
     * Passes deltas through while remembering whether any arrived, which is
     * what decides if a failure may be retried. onDone and onError are held
     * back so that {@link #stream} is the only place that ends a turn.
     */
    private static final class Counting implements LlmStreamHandler {

        private final LlmStreamHandler delegate;
        private boolean spoke;

        private Counting(LlmStreamHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onTextDelta(String text) {
            spoke = true;
            delegate.onTextDelta(text);
        }

        @Override
        public void onToolCall(String name, String argumentsJson) {
            spoke = true;
            delegate.onToolCall(name, argumentsJson);
        }

        @Override
        public void onDone() {
        }

        @Override
        public void onError(Throwable error) {
        }
    }
}
