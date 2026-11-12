package com.jtalk.livecommunication.domain.dto.response;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable.Serializable
public record ParticipantResponse(String userId, ParticipantRole role, PresenceStatus status) {
}
