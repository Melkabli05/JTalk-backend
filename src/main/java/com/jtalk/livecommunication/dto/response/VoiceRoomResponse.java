package com.jtalk.livecommunication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Room metadata (PostgreSQL) merged with its real-time numbers (Redis). Empty lists stay in the payload. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable.Serializable
public record VoiceRoomResponse(
        UUID id,
        String title,
        String topic,
        UUID hostId,
        String targetLanguage,
        String nativeLanguage,
        int maxStageSeats,
        VoiceRoomStatus status,
        Instant createdAt,
        List<UUID> speakers,
        long participantCount,
        long handRaiseCount) {
}
