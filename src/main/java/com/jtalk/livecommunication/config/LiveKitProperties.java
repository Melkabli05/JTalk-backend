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
        @Bindable(defaultValue = "PT2H") Duration tokenTtl) {

    /** The Room Service API speaks http(s) on the same host as the websocket URL. */
    public String apiUrl() {
        return url.replaceFirst("^ws", "http");
    }
}
