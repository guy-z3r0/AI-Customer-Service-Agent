package com.ulab.agent.brain.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;

/**
 * Google's Gemini, over its streamGenerateContent endpoint.
 *
 * Two things about Gemini's shape are worth knowing here: the agent's own past
 * lines are labelled "model" rather than "assistant", and the standing
 * instructions travel in their own field instead of as a first message.
 */
public class GeminiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;

    public GeminiProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public void chatStream(LlmRequest request, LlmStreamHandler handler) {
        String url = BASE + request.model() + ":streamGenerateContent?alt=sse";
        String body = buildBody(request).toString();

        SseChat.stream(id(),
                () -> SseChat.request(url)
                        .header("x-goog-api-key", apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                handler, GeminiProvider::readChunk);
    }

    private static JsonObject buildBody(LlmRequest request) {
        JsonObject body = new JsonObject();
        body.add("systemInstruction", parts(request.system()));

        JsonArray contents = new JsonArray();
        for (LlmRequest.Message message : request.messages()) {
            JsonObject turn = new JsonObject();
            turn.addProperty("role", message.isAgent() ? "model" : "user");
            turn.add("parts", parts(message.text()).getAsJsonArray("parts"));
            contents.add(turn);
        }
        body.add("contents", contents);

        JsonObject generation = new JsonObject();
        generation.addProperty("temperature", request.temperature());
        body.add("generationConfig", generation);

        addTools(body, request);
        return body;
    }

    /** Gemini takes every tool as one declaration list rather than one entry each. */
    private static void addTools(JsonObject body, LlmRequest request) {
        if (request.toolSchemas().isEmpty()) return;

        JsonArray declarations = new JsonArray();
        request.toolSchemas().forEach(schema -> declarations.add(JsonParser.parseString(schema)));
        JsonObject tool = new JsonObject();
        tool.add("functionDeclarations", declarations);

        JsonArray tools = new JsonArray();
        tools.add(tool);
        body.add("tools", tools);
    }

    private static JsonObject parts(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text == null ? "" : text);
        JsonArray list = new JsonArray();
        list.add(part);
        JsonObject wrapper = new JsonObject();
        wrapper.add("parts", list);
        return wrapper;
    }

    private static void readChunk(String json, LlmStreamHandler out) {
        JsonArray parts = firstCandidateParts(json);
        if (parts == null) return;

        for (JsonElement element : parts) {
            JsonObject part = element.getAsJsonObject();
            if (part.has("text")) {
                out.onTextDelta(part.get("text").getAsString());
            } else if (part.has("functionCall")) {
                JsonObject call = part.getAsJsonObject("functionCall");
                JsonElement args = call.get("args");
                out.onToolCall(call.get("name").getAsString(),
                        args == null ? "{}" : args.toString());
            }
        }
    }

    private static JsonArray firstCandidateParts(String json) {
        try {
            JsonObject chunk = JsonParser.parseString(json).getAsJsonObject();
            JsonArray candidates = chunk.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) return null;

            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            return content == null ? null : content.getAsJsonArray("parts");
        } catch (RuntimeException e) {
            log.debug("Ignoring a Gemini chunk that did not parse: {}", e.toString());
            return null;
        }
    }
}
