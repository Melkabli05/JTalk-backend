package com.jtalk.livecommunication.dev;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** A person's profile as a client registers it with the dev profile. */
@Serdeable.Deserializable
public record DevUserRequest(
        @NotNull UUID id,
        @NotBlank @Size(max = 40) String name,
        @NotBlank @Size(max = 10) String nativeLanguage,
        @NotBlank @Size(max = 10) String learning,
        @Nullable @Size(max = 40) String avatar) {
}
