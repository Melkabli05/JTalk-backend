package com.jtalk.livecommunication.domain.model;

import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/**
 * A participant's profile was updated (e.g., picture changed).
 */
@Serdeable
public record ParticipantProfileUpdate(UUID userId, String avatarUrl) {
}
