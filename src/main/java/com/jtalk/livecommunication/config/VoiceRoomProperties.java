package com.jtalk.livecommunication.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/** Tunables for ephemeral real-time state, bound from the {@code voice-room.*} properties. */
@ConfigurationProperties("voice-room")
public record VoiceRoomProperties(
        /* A participant whose media connection LiveKit has not confirmed within this long of joining is no longer listed as present. */
        @Bindable(defaultValue = "PT45S") Duration presenceTimeout,
        /* Safety net: ephemeral Redis keys of abandoned rooms expire after this long. */
        @Bindable(defaultValue = "PT24H") Duration stateTtl,
        /* How long a disconnected speaker keeps their seat before it is reclaimed. */
        @Bindable(defaultValue = "PT30S") Duration leaseTtl) {
}
