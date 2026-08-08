package com.ulab.agent.brain;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The calls that are happening right now, by call id.
 *
 * There is a ceiling on how many. Every live call holds a websocket, a
 * recogniser, a voice and a share of the model's quota, and nothing else in the
 * system counts them — so without a limit here, opening calls in a loop is a
 * way to spend somebody's month of model credit in an afternoon.
 */
@Component
public class CallRegistry {

    /**
     * How many calls may run at once.
     *
     * One operator with one microphone is placing these, so anything above a
     * handful is a mistake or an attack rather than a busy afternoon.
     */
    private static final int MAX_LIVE_CALLS = 5;

    private final Map<UUID, CallSession> live = new ConcurrentHashMap<>();

    /** @return false when the system is already carrying as much as it will */
    public boolean hasRoom() {
        return live.size() < MAX_LIVE_CALLS;
    }

    public void add(CallSession session) {
        live.put(session.callId(), session);
    }

    public CallSession get(UUID callId) {
        return live.get(callId);
    }

    public CallSession remove(UUID callId) {
        return live.remove(callId);
    }

    public Collection<CallSession> all() {
        return live.values();
    }

    public int count() {
        return live.size();
    }
}
