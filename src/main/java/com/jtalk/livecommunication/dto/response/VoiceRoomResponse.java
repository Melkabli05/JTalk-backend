package com.jtalk.livecommunication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.model.RoomSpeakerSummary;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Room metadata (PostgreSQL) merged with its real-time numbers (Redis). Empty lists stay in the payload. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
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
        long handRaiseCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable RoomSpeakerSummary host,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<RoomSpeakerSummary> speakerProfiles) {

    public VoiceRoomResponse(
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
        this(id, title, topic, hostId, targetLanguage, nativeLanguage, maxStageSeats, status,
                createdAt, speakers, participantCount, handRaiseCount, null, null);
    }

    public VoiceRoomResponse withProfiles(@Nullable RoomSpeakerSummary host,
                                          @Nullable List<RoomSpeakerSummary> speakerProfiles) {
        return new VoiceRoomResponse(id, title, topic, hostId, targetLanguage, nativeLanguage, maxStageSeats,
                status, createdAt, speakers, participantCount, handRaiseCount, host, speakerProfiles);
    }
}
