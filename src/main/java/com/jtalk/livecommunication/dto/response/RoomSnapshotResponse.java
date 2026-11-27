package com.jtalk.livecommunication.dto.response;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.model.RoomJoinSnapshot;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable.Serializable
public record RoomSnapshotResponse(VoiceRoomResponse room, ParticipantRole role, RoomJoinSnapshot snapshot,
                                   boolean isModerator) {

    public RoomSnapshotResponse(VoiceRoomResponse room, ParticipantRole role, RoomJoinSnapshot snapshot) {
        this(room, role, snapshot, false);
    }
}
