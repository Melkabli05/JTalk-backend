package com.jtalk.livecommunication.domain.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

@Serdeable.Deserializable
public record WhisperRequest(@NotBlank String targetUserId) {
}
