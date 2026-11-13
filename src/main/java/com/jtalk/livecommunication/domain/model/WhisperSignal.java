package com.jtalk.livecommunication.domain.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/**
 * Data message to one side of a whisper. "start" carries that side's token for the whisper's private room; "stop"
 * means disconnect from it.
 */
@Serdeable
public record WhisperSignal(String action, UUID speakerId, UUID targetId, @Nullable String token) {
}
