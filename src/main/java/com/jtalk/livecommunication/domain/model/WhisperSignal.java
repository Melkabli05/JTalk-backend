package com.jtalk.livecommunication.domain.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Data message to the whisper target. "start" carries the connection to the private room; "stop" means disconnect
 * from it.
 */
@Serdeable
public record WhisperSignal(String action, String speakerId, @Nullable String mediaUrl, @Nullable String mediaToken) {
}
