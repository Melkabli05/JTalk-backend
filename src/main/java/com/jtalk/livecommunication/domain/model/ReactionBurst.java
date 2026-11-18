package com.jtalk.livecommunication.domain.model;

import com.jtalk.livecommunication.domain.enums.Reaction;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/** One reaction by one person, as clients receive it inside a {@code REACTIONS} event. */
@Serdeable
public record ReactionBurst(UUID userId, Reaction reaction) {
}
