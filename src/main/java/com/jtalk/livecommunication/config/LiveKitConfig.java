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
public record LiveKitConfig(
        @NotBlank String url,
        @NotBlank String apiKey,
        @NotBlank String apiSecret,
        /* A join token is only checked when connecting, which takes seconds, and every join issues a fresh one, so it is short-lived: a copy
           taken from the page is useless almost at once. */
        @Bindable(defaultValue = "PT2M") Duration tokenTtl,
        /* LiveKit closes a room this many seconds after the last participant leaves. */
        @Bindable(defaultValue = "120") int emptyRoomTimeoutSeconds,
        /* LiveKit closes a room this many seconds after creation if nobody ever connects. */
        @Bindable(defaultValue = "60") int departureRoomTimeoutSeconds,
        /* The longest one call to the media server may take. A slow or hung LiveKit then fails the request in this long (a 502) instead of
           holding it open for OkHttp's default of 10 s each for connecting, writing and reading. */
        @Bindable(defaultValue = "PT8S") Duration requestTimeout) {

    /** The Room Service API speaks http(s) on the same host as the websocket URL. */
    public String apiUrl() {
        return url.replaceFirst("^ws", "http");
    }
}
