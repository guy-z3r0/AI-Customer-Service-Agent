package com.ulab.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulab.agent.brain.CallRegistry;
import com.ulab.agent.brain.ConversationBrain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.UUID;

/**
 * The line between the voice server and the brain, one socket per call, at
 * /ws/turn/{callId}.
 *
 * Only text crosses it. Audio never comes near Java — it goes from the caller's
 * browser to the voice server and back, and this socket carries the words that
 * fall out of it in each direction:
 *
 *   voice server -> here   call_start, caller_speaking, caller_stopped,
 *                          transcript_partial, transcript_final, spoken,
 *                          agent_done, call_end
 *   here -> voice server   greeting, say, set_language, hangup
 */
@Component
public class TurnSocket extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TurnSocket.class);

    private static final String CALL_ID = "callId";

    private final ConversationBrain brain;
    private final CallRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    public TurnSocket(ConversationBrain brain, CallRegistry registry) {
        this.brain = brain;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        UUID callId = callIdIn(socket);
        if (callId == null) {
            close(socket, CloseStatus.BAD_DATA.withReason("no call id in the path"));
            return;
        }
        socket.getAttributes().put(CALL_ID, callId);
        log.debug("Voice server opened the turn socket for call {}", callId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        UUID callId = (UUID) socket.getAttributes().get(CALL_ID);
        if (callId == null) return;

        JsonNode body;
        try {
            body = json.readTree(message.getPayload());
        } catch (Exception notJson) {
            log.warn("[{}] ignoring a turn message that was not JSON", callId);
            return;
        }
        route(socket, callId, body.path("type").asText(""), body);
    }

    /**
     * A voice server that goes away without saying goodbye does not end the
     * call outright — it is given a few seconds to dial back in first, which
     * the brain handles. Without this the record would sit open for ever and
     * the panel would keep showing a call nobody is on.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        UUID callId = (UUID) socket.getAttributes().get(CALL_ID);
        if (callId == null || registry.get(callId) == null) return;

        log.info("[{}] the voice server's turn socket closed ({})", callId, status.getCode());
        brain.onLinkClosed(callId, socket);
    }

    // ------------------------------------------------------------ internals --

    private void route(WebSocketSession socket, UUID callId, String type, JsonNode body) {
        try {
            switch (type) {
                case "call_start" -> start(socket, callId, body);
                case "transcript_partial" -> brain.onTranscriptPartial(callId, body.path("text").asText());
                case "transcript_final" -> brain.onTranscriptFinal(callId,
                        body.path("text").asText(), body.path("tSttFinal").asLong());
                case "spoken" -> brain.onSpoken(callId, body.path("seq").asInt(),
                        body.path("tTtsFirst").asLong());
                case "caller_speaking" -> brain.onCallerSpeaking(callId);
                case "caller_stopped" -> brain.onCallerStopped(callId);
                case "agent_done" -> brain.onAgentDone(callId);
                case "call_end" -> brain.onCallEnd(callId, body.path("reason").asText("hangup"));
                default -> log.warn("[{}] the voice server sent an unknown message: {}", callId, type);
            }
        } catch (RuntimeException e) {
            log.warn("[{}] a '{}' message could not be handled: {}", callId, type, e.toString());
        }
    }

    private void start(WebSocketSession socket, UUID callId, JsonNode body) {
        try {
            brain.onCallStart(callId, body.path("languageHint").asText(null),
                    socket, message -> send(socket, message));
        } catch (RuntimeException e) {
            log.warn("[{}] the call could not be started: {}", callId, e.toString());
            send(socket, Map.of("type", "hangup", "reason", "call_unknown", "farewellText", ""));
            close(socket, CloseStatus.NORMAL);
        }
    }

    /** A websocket session is not safe for two threads, and turns run on their own. */
    private void send(WebSocketSession socket, Map<String, Object> message) {
        try {
            String text = json.writeValueAsString(message);
            synchronized (socket) {
                if (socket.isOpen()) socket.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            log.debug("Could not send a '{}' message: {}", message.get("type"), e.toString());
        }
    }

    private static UUID callIdIn(WebSocketSession socket) {
        if (socket.getUri() == null) return null;
        String path = socket.getUri().getPath();
        String tail = path.substring(path.lastIndexOf('/') + 1);
        try {
            return UUID.fromString(tail);
        } catch (IllegalArgumentException notAUuid) {
            log.warn("A turn socket was opened at {}, which names no call", path);
            return null;
        }
    }

    private static void close(WebSocketSession socket, CloseStatus status) {
        try {
            socket.close(status);
        } catch (Exception e) {
            log.debug("Could not close a turn socket: {}", e.toString());
        }
    }
}
