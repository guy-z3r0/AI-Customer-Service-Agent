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

    public WsConfig(LiveEventSocket liveEvents, TurnSocket turns) {
        this.liveEvents = liveEvents;
        this.turns = turns;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveEvents, "/ws/live");
        // The voice server is a program, not a page, so it sends no Origin
        // header and the same-origin rule lets it through untouched.
        registry.addHandler(turns, "/ws/turn/*");
    }
}
