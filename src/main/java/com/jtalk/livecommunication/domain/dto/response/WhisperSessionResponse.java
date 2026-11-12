package com.jtalk.livecommunication.domain.dto.response;

import io.micronaut.serde.annotation.Serdeable;

/** How the speaker connects to their whisper's private room. */
@Serdeable.Serializable
public record WhisperSessionResponse(String mediaUrl, String mediaToken) {
}
