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
 * A call in the middle of a turn is not silent, it is waiting on a model, and
 * is left alone.
 */
@Component
public class InactivityWatchdog {

    private static final Logger log = LoggerFactory.getLogger(InactivityWatchdog.class);

    private static final long SWEEP_MS = 3000;

    private static final int DEFAULT_WARN_S = 20;
    private static final int DEFAULT_HANGUP_S = 40;

    private final CallRegistry registry;
    private final ConversationBrain brain;
    private final ConfigService config;

    public InactivityWatchdog(CallRegistry registry, ConversationBrain brain, ConfigService config) {
        this.registry = registry;
        this.brain = brain;
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
        if (session.isBusy() || session.isEnding()) return;

        try {
            if (silentSeconds >= hangUpAfter) {
                brain.hangUpForSilence(session);
            } else if (silentSeconds >= warnAfter && session.firstSilenceWarning()) {
                brain.warnAboutSilence(session);
            }
        } catch (RuntimeException e) {
            // One stuck call must not stop the sweep reaching the others.
            log.warn("[{}] could not act on the silence: {}", session.callId(), e.toString());
        }
    }

    private static long silenceOf(CallSession session, Instant now) {
        return Duration.between(session.lastActivity(), now).getSeconds();
    }
}
