package com.jtalk.livecommunication.domain.dto.response;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import io.micronaut.serde.annotation.Serdeable;

/** What a user gets when they enter a room or take a seat: the room, their LiveKit token and their rights. */
@Serdeable.Serializable
public record RoomSessionResponse(VoiceRoomResponse room, String liveKitToken, ParticipantRole role, boolean canPublishAudio) {
}
