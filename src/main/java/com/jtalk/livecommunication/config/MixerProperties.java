package com.jtalk.livecommunication.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

@ConfigurationProperties("voice-room.mixer")
public record MixerProperties(
        /* Redis Stream the mixer workers consume assignments from. */
        @Bindable(defaultValue = "mixer:assignments") String stream,
        /* Name of the mixed mono track the mixer publishes into the room. */
        @Bindable(defaultValue = "mcu_mono_fallback") String fallbackTrackName,
        /* Presence count at which the room also gets an audience broadcast mix. */
        @Bindable(defaultValue = "100") int audienceThreshold) {
}
