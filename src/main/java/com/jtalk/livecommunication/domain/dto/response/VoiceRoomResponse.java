package com.jtalk.livecommunication.domain.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Room metadata (PostgreSQL) merged with its real-time numbers (Redis). Empty lists stay in the payload. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable.Serializable
public record VoiceRoomResponse(
        UUID id,
        String name,
        String hostId,
        int maxSpeakers,
        VoiceRoomStatus status,
        Instant createdAt,
        List<String> speakers,
        long participantCount,
        long handRaiseCount) {

    public static VoiceRoomResponse from(VoiceRoom room, RoomLiveStats live) {
        return new VoiceRoomResponse(room.id(), room.name(), room.hostId(), room.maxSpeakers(), room.status(),
                room.createdAt(), live.speakers(), live.participantCount(), live.handRaiseCount());
    }
}
