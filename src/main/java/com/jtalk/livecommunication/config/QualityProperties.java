package com.jtalk.livecommunication.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/** Thresholds for switching a struggling client between individual speaker tracks and the mixed fallback. */
@ConfigurationProperties("voice-room.quality")
public record QualityProperties(
        @Bindable(defaultValue = "15") double degradedLossPercent,
        @Bindable(defaultValue = "25") int degradedDownstreamKbps,
        @Bindable(defaultValue = "3") double recoveryLossPercent,
        /* Loss must stay below recoveryLossPercent for this long before switching back. */
        @Bindable(defaultValue = "PT20S") Duration recoveryWindow) {
}
