package com.jtalk.livecommunication.domain.dto.response;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable.Serializable
public record JoinRoomResponse(String mediaUrl, String mediaToken, ParticipantRole role) {
}
