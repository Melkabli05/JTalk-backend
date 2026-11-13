package com.jtalk.livecommunication.domain.dto.response;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable.Serializable
public record ParticipantResponse(UUID userId, ParticipantRole role, PresenceStatus status) {
}
