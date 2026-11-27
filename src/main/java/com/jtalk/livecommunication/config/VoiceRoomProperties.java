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
        @Bindable(defaultValue = "PT30S") Duration leaseTtl,
        /* How long a disconnected host keeps the room before host succession or closure. */
        @Bindable(defaultValue = "PT120S") Duration hostLeaseTtl,
        /* How long a solo host can remain alone before receiving an idle warning. */
        @Bindable(defaultValue = "PT10M") Duration soloRoomTtl,
        /* How long a warned solo host has to tap Stay Live before the room closes. */
        @Bindable(defaultValue = "PT60S") Duration idleWarningTtl,
        /* How many comments one person may post in a room per window. */
        @Bindable(defaultValue = "3") int commentsPerWindow,
        /* The window the comment limit counts in. */
        @Bindable(defaultValue = "PT5S") Duration commentWindow) {
}
