package com.jtalk.livecommunication.dto.response;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import io.micronaut.serde.annotation.Serdeable;

/**
 * What a user gets when they enter a room or take a seat: the room, where and with what token to connect to its audio
 * (LiveKit), and their rights. Rights change while connected (the server updates them), so the token needs no refresh.
 */
@Serdeable.Serializable
public record RoomSessionResponse(VoiceRoomResponse room, String liveKitUrl, String liveKitToken, ParticipantRole role,
                                  boolean canPublishAudio) {
}
