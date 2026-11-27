package com.jtalk.livecommunication.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Batch lookup of live-room presence for up to 500 users at once. */
@Serdeable.Deserializable
public record BatchPresenceRequest(
        @NotEmpty @Size(max = 500) List<@NotNull UUID> userIds) {
}
