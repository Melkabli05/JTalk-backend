package com.jtalk.livecommunication.dto.response;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable.Serializable
public record ParticipantResponse(UUID userId, ParticipantRole role, PresenceStatus status, boolean isModerator) {

    public ParticipantResponse(UUID userId, ParticipantRole role, PresenceStatus status) {
        this(userId, role, status, false);
    }
}
