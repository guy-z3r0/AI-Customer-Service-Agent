package com.ulab.agent.brain;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The calls that are happening right now, by call id. */
@Component
public class CallRegistry {

    private final Map<UUID, CallSession> live = new ConcurrentHashMap<>();

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
