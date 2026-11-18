package com.jtalk.livecommunication.service.impl;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets one person react only so often in one room. It lives in memory on purpose: a reaction is gone in a moment, so a restart
 * forgetting the last one costs nothing, and refusing a spammer must not cost a Redis command.
 */
@Singleton
class ReactionThrottle {

    private final Clock clock;
    private final Duration interval;
    private final Map<String, Instant> lastAllowed = new ConcurrentHashMap<>();

    ReactionThrottle(Clock clock, @Value("${voice-room.reaction-interval:500ms}") Duration interval) {
        this.clock = clock;
        this.interval = interval;
    }

    boolean allow(UUID roomId, UUID userId) {
        Instant now = clock.instant();
        boolean[] allowed = {false};
        lastAllowed.compute(roomId + ":" + userId, (key, last) -> {
            if (last == null || !now.isBefore(last.plus(interval))) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        return allowed[0];
    }

    @Scheduled(fixedDelay = "30s")
    void forgetOldEntries() {
        Instant cutoff = clock.instant().minus(interval);
        lastAllowed.values().removeIf(last -> last.isBefore(cutoff));
    }
}
