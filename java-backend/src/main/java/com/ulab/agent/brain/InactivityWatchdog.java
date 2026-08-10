package com.ulab.agent.brain;

import com.ulab.agent.services.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Watches for callers who have gone quiet.
 *
 * A phone line that has fallen silent is ambiguous — someone put the handset
 * down, or the connection died, or they are reading something out to
 * themselves — so the agent asks once before deciding. Both waits come from
 * Settings, so an owner whose customers think slowly can lengthen them without
 * touching any of this.
 *
 * Three kinds of quiet are not silence and are all left alone: a call waiting on
 * a model, a call where the agent's own audio is still playing, and a call where
 * the caller is in the middle of a sentence. The last two are why an agent used
 * to ask "are you still there?" over the top of its own greeting, and over a
 * caller who was answering it — the free recogniser says nothing at all until a
 * sentence is finished, so a caller talking for twenty seconds looked identical
 * to an empty room.
 */
@Component
public class InactivityWatchdog {

    private static final Logger log = LoggerFactory.getLogger(InactivityWatchdog.class);

    private static final long SWEEP_MS = 3000;

    private static final int DEFAULT_WARN_S = 20;
    private static final int DEFAULT_HANGUP_S = 40;

    private final CallRegistry registry;
    private final CallInterventions interventions;
    private final ConfigService config;

    public InactivityWatchdog(CallRegistry registry, CallInterventions interventions,
                              ConfigService config) {
        this.registry = registry;
        this.interventions = interventions;
        this.config = config;
    }

    @Scheduled(fixedDelay = SWEEP_MS)
    public void sweep() {
        if (registry.count() == 0) return;  // the usual case: nothing to read, nothing to do

        int warnAfter = config.getInt("inactivity_warn_s", DEFAULT_WARN_S);
        int hangUpAfter = config.getInt("inactivity_hangup_s", DEFAULT_HANGUP_S);
        Instant now = Instant.now();

        for (CallSession session : registry.all()) {
            check(session, silenceOf(session, now), warnAfter, hangUpAfter);
        }
    }

    private void check(CallSession session, long silentSeconds, int warnAfter, int hangUpAfter) {
        if (isSomebodyTalking(session) || session.isEnding()) return;

        try {
            if (silentSeconds >= hangUpAfter) {
                interventions.hangUpForSilence(session);
            } else if (silentSeconds >= warnAfter && session.firstSilenceWarning()) {
                interventions.warnAboutSilence(session);
            }
        } catch (RuntimeException e) {
            // One stuck call must not stop the sweep reaching the others.
            log.warn("[{}] could not act on the silence: {}", session.callId(), e.toString());
        }
    }

    /** True when the line is not free for the agent to say anything on. */
    private static boolean isSomebodyTalking(CallSession session) {
        return session.isBusy() || session.agentHasTheFloor() || session.callerIsSpeaking();
    }

    private static long silenceOf(CallSession session, Instant now) {
        return Duration.between(session.lastActivity(), now).getSeconds();
    }
}
