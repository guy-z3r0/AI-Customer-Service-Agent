package com.ulab.agent.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Where the websocket endpoints are mounted.
 *
 * Only same-origin connections are allowed, which is Spring's default: the
 * panel is served from this same server, and there is no login to protect the
 * feed with if a page from somewhere else could open it.
 */
@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private final LiveEventSocket liveEvents;
    private final TurnSocket turns;
    private final TwilioMediaSocket twilioMedia;

    public WsConfig(LiveEventSocket liveEvents, TurnSocket turns, TwilioMediaSocket twilioMedia) {
        this.liveEvents = liveEvents;
        this.turns = turns;
        this.twilioMedia = twilioMedia;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveEvents, "/ws/live");
        // The voice server is a program, not a page, so it sends no Origin
        // header and the same-origin rule lets it through untouched.
        registry.addHandler(turns, "/ws/turn/*");
        // Twilio's media servers are the same kind of client, and this is the
        // one address they need: it relays on to the voice server itself, so a
        // telephone call needs only one public tunnel rather than two.
        registry.addHandler(twilioMedia, "/ws/twilio");
    }
}
