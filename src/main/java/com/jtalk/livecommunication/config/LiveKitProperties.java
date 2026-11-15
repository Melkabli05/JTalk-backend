package com.jtalk.livecommunication.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotBlank;

import java.time.Duration;

/**
 * LiveKit connection settings. Bind them from {@code LIVEKIT_URL}, {@code LIVEKIT_API_KEY} and
 * {@code LIVEKIT_API_SECRET} (or the equivalent {@code livekit.*} properties).
 *
 * @param url websocket URL clients connect to, e.g. {@code wss://livekit.example.com}
 */
@ConfigurationProperties("livekit")
public record LiveKitProperties(
        @NotBlank String url,
        @NotBlank String apiKey,
        @NotBlank String apiSecret,
        /* A join token is used within seconds of being issued, so it is short-lived: a copy taken from the page is soon useless. */
        @Bindable(defaultValue = "PT5M") Duration tokenTtl,
        /* LiveKit closes a room this many seconds after the last participant leaves. */
        @Bindable(defaultValue = "300") int emptyRoomTimeoutSeconds) {

    /** The Room Service API speaks http(s) on the same host as the websocket URL. */
    public String apiUrl() {
        return url.replaceFirst("^ws", "http");
    }
}
