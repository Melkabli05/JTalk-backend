package com.jtalk.livecommunication.domain.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Serdeable.Deserializable
public record WhisperRequest(@NotNull UUID targetUserId) {
}
