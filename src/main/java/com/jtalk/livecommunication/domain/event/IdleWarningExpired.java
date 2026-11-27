package com.jtalk.livecommunication.domain.event;

import java.util.UUID;

/** Published when a warned solo host does not send a keepalive in time. */
public record IdleWarningExpired(UUID roomId) {
}
