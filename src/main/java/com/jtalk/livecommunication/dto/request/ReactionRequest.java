package com.jtalk.livecommunication.dto.request;

import com.jtalk.livecommunication.domain.enums.Reaction;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

@Serdeable.Deserializable
public record ReactionRequest(@NotNull Reaction reaction) {
}
