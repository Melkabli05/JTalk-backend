package com.jtalk.livecommunication.domain.dto.response;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import com.jtalk.livecommunication.domain.model.Participant;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable.Serializable
public record ParticipantResponse(String userId, ParticipantRole role, PresenceStatus status) {

    public static ParticipantResponse from(Participant participant) {
        return new ParticipantResponse(participant.userId(), participant.role(), participant.status());
    }
}
