package com.ulab.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The panel's live feed at /ws/live.
 *
 * Everything interesting that happens on a call — a line of transcript, a mode
 * change, a latency reading — is pushed to every open panel from here. It is
 * one-way: the panel listens and never sends anything back, because every
 * action it can take already has a REST endpoint.
 *
 * Nobody listening is the normal case, and broadcasting into an empty room
 * costs nothing, so no part of the call flow ever waits on this.
 */
@Component
public class LiveEventSocket extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveEventSocket.class);

    private final Set<WebSocketSession> listeners = new CopyOnWriteArraySet<>();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        listeners.add(session);
        log.debug("Panel connected to the live feed ({} listening)", listeners.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        listeners.remove(session);
        log.debug("Panel left the live feed ({} listening)", listeners.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        log.debug("Live feed transport error: {}", error.toString());
        listeners.remove(session);
    }

    /**
     * Sends one event to every open panel.
     *
     * @param type    the event name: call_started, line, partial, latency,
     *                mode_change, language_change, call_ended, health
     * @param payload the rest of the event, merged in alongside the type
     */
    public void broadcast(String type, Map<String, Object> payload) {
        if (listeners.isEmpty()) return;

        String text;
        try {
            text = json.writeValueAsString(withType(type, payload));
        } catch (IOException e) {
            log.warn("Could not turn a '{}' event into JSON: {}", type, e.toString());
            return;
        }

        TextMessage message = new TextMessage(text);
        for (WebSocketSession listener : listeners) {
            send(listener, message);
        }
    }

    private void send(WebSocketSession listener, TextMessage message) {
        try {
            if (listener.isOpen()) {
                // A session is not safe for two threads at once, and call
                // events can arrive from several.
                synchronized (listener) {
                    listener.sendMessage(message);
                }
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping a panel that stopped listening: {}", e.toString());
            listeners.remove(listener);
        }
    }

    private static Map<String, Object> withType(String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        if (payload != null) event.putAll(payload);
        return event;
    }
}
