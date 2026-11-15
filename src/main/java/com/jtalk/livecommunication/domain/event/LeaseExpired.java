package com.jtalk.livecommunication.domain.event;

import java.util.UUID;

/**
 * Published when a speaker's grace lease runs out (they dropped and did not come back in time), so whoever owns the stage can reclaim the
 * seat. The Redis expiry listener only reports it; it does not know what is done about it.
 */
public record LeaseExpired(UUID roomId, UUID userId) {
}
