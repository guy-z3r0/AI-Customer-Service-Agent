package com.ulab.agent.brain.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI's chat completions endpoint, in streaming mode.
 *
 * The awkward part of this format is that a tool call arrives in pieces — the
 * name in one chunk and its arguments a character at a time after it — so the
 * fragments are collected here and reported once, when the model says it has
 * finished asking.
 */
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;

    public OpenAiProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public void chatStream(LlmRequest request, LlmStreamHandler handler) {
        String body = buildBody(request).toString();
        PendingToolCalls pending = new PendingToolCalls();

        SseChat.stream(id(),
                () -> SseChat.request(URL)
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                handler, (json, out) -> readChunk(json, out, pending));
    }

    private static JsonObject buildBody(LlmRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("stream", true);
        body.addProperty("temperature", request.temperature());

        JsonArray messages = new JsonArray();
        messages.add(message("system", request.system()));
        for (LlmRequest.Message message : request.messages()) {
            messages.add(message(message.role(), message.text()));
        }
        body.add("messages", messages);

        addTools(body, request);
        return body;
    }

    private static void addTools(JsonObject body, LlmRequest request) {
        if (request.toolSchemas().isEmpty()) return;

        JsonArray tools = new JsonArray();
        for (String schema : request.toolSchemas()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", JsonParser.parseString(schema));
            tools.add(tool);
        }
        body.add("tools", tools);
    }

    private static JsonObject message(String role, String content) {
        JsonObject node = new JsonObject();
        node.addProperty("role", role);
        node.addProperty("content", content == null ? "" : content);
        return node;
    }

    private static void readChunk(String json, LlmStreamHandler out, PendingToolCalls pending) {
        JsonObject choice = firstChoice(json);
        if (choice == null) return;

        JsonObject delta = choice.getAsJsonObject("delta");
        if (delta != null) {
            JsonElement content = delta.get("content");
            if (content != null && content.isJsonPrimitive()) {
                out.onTextDelta(content.getAsString());
            }
            pending.collect(delta.getAsJsonArray("tool_calls"));
        }

        JsonElement reason = choice.get("finish_reason");
        if (reason != null && reason.isJsonPrimitive()) pending.flush(out);
    }

    private static JsonObject firstChoice(String json) {
        try {
            JsonObject chunk = JsonParser.parseString(json).getAsJsonObject();
            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return null;
            return choices.get(0).getAsJsonObject();
        } catch (RuntimeException e) {
            log.debug("Ignoring an OpenAI chunk that did not parse: {}", e.toString());
            return null;
        }
    }

    /** Tool calls being spelled out one fragment at a time, keyed by their slot. */
    private static final class PendingToolCalls {

        private final Map<Integer, Slot> byIndex = new LinkedHashMap<>();

        /** @param fragments the delta's tool_calls array, or null when it has none */
        void collect(JsonArray fragments) {
            if (fragments == null) return;

            for (JsonElement element : fragments) {
                JsonObject fragment = element.getAsJsonObject();
                JsonObject function = fragment.getAsJsonObject("function");
                if (function == null) continue;

                Slot slot = slotFor(fragment, function);
                if (function.has("arguments")) {
                    slot.arguments.append(function.get("arguments").getAsString());
                }
            }
        }

        /**
         * A fragment carrying an id opens a new call; every fragment after it
         * adds to the same one. Opening also throws away a half-collected call
         * left behind by an attempt that failed and was retried.
         */
        private Slot slotFor(JsonObject fragment, JsonObject function) {
            int index = fragment.has("index") ? fragment.get("index").getAsInt() : 0;
            if (fragment.has("id") || !byIndex.containsKey(index)) {
                byIndex.put(index, new Slot(name(function)));
            }
            return byIndex.get(index);
        }

        void flush(LlmStreamHandler out) {
            byIndex.values().forEach(slot -> out.onToolCall(slot.name,
                    slot.arguments.isEmpty() ? "{}" : slot.arguments.toString()));
            byIndex.clear();
        }

        private static String name(JsonObject function) {
            return function.has("name") ? function.get("name").getAsString() : "";
        }
    }

    private static final class Slot {
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        private Slot(String name) {
            this.name = name;
        }
    }
}
