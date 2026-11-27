package com.jtalk.livecommunication.domain.event;

import java.util.UUID;

/** Published when a disconnected host's grace lease expires. */
public record HostLeaseExpired(UUID roomId) {
}
