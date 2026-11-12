package com.jtalk.livecommunication.domain.model;

import io.micronaut.serde.annotation.Serdeable;

/** Data message sent to the whisper speaker and target so their clients can react. */
@Serdeable
public record WhisperSignal(String action, String speakerId, String targetId, String track) {
}
