package com.jtalk.livecommunication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.model.RoomJoinSnapshot;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * What a user gets when they enter a room or take a seat: the room, where and with what token to connect to its audio
 * (LiveKit), and their rights. Rights change while connected (the server updates them), so the token needs no refresh.
 */
@Serdeable.Serializable
public record RoomSessionResponse(VoiceRoomResponse room, String mediaUrl, String mediaToken, ParticipantRole role,
                                  boolean canPublishAudio, boolean isChatMuted, boolean isSelfMuted, boolean isMutedByHost,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable RoomJoinSnapshot snapshot,
                                  boolean isModerator) {

    public RoomSessionResponse(VoiceRoomResponse room, String mediaUrl, String mediaToken, ParticipantRole role,
                               boolean canPublishAudio, boolean isChatMuted, @Nullable RoomJoinSnapshot snapshot) {
        this(room, mediaUrl, mediaToken, role, canPublishAudio, isChatMuted, false, false, snapshot, false);
    }

    public RoomSessionResponse(VoiceRoomResponse room, String mediaUrl, String mediaToken, ParticipantRole role,
                               boolean canPublishAudio, boolean isChatMuted, @Nullable RoomJoinSnapshot snapshot,
                               boolean isModerator) {
        this(room, mediaUrl, mediaToken, role, canPublishAudio, isChatMuted, false, false, snapshot, isModerator);
    }
}
