package com.jtalk.livecommunication.domain.dto.response;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable.Serializable
public record VoiceRoomResponse(
        UUID id,
        String name,
        String hostId,
        int maxSpeakers,
        VoiceRoomStatus status,
        Instant createdAt) {

    public static VoiceRoomResponse from(VoiceRoom room) {
        return new VoiceRoomResponse(room.id(), room.name(), room.hostId(), room.maxSpeakers(), room.status(), room.createdAt());
    }
}
