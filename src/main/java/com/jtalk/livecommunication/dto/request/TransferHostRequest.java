package com.jtalk.livecommunication.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Transfers the host role from the current host to any active participant in the same room. */
@Serdeable.Deserializable
public record TransferHostRequest(@NotNull UUID toUserId) {
}
