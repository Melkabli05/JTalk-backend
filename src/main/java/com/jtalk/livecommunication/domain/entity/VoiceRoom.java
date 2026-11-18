package com.jtalk.livecommunication.domain.entity;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.AutoPopulated;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.DateUpdated;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent room metadata. Real-time state (who is on stage, who is present) is NOT stored here; it lives in Redis.
 *
 * @param maxStageSeats stage seats including the host, who is seated when the room is created
 */
@MappedEntity("voice_rooms")
public record VoiceRoom(
        @Id @AutoPopulated UUID id,
        String title,
        @Nullable String topic,
        UUID hostId,
        String targetLanguage,
        String nativeLanguage,
        int maxStageSeats,
        VoiceRoomStatus status,
        @DateCreated Instant createdAt,
        @DateUpdated Instant updatedAt) {

    /** A new, unsaved room: the id and timestamps are filled in by Micronaut Data when it is inserted. */
    public static VoiceRoom open(String title, @Nullable String topic, UUID hostId, String targetLanguage,
                                 String nativeLanguage, int maxStageSeats) {
        return new VoiceRoom(null, title.strip(), topic, hostId, targetLanguage, nativeLanguage, maxStageSeats,
                VoiceRoomStatus.ACTIVE, null, null);
    }

    /** Applies a partial update; a null argument keeps the current value. */
    public VoiceRoom withSettings(@Nullable String newTitle, @Nullable String newTopic, @Nullable Integer newMaxStageSeats) {
        return new VoiceRoom(id, newTitle == null ? title : newTitle.strip(), newTopic == null ? topic : newTopic, hostId,
                targetLanguage, nativeLanguage, newMaxStageSeats == null ? maxStageSeats : newMaxStageSeats,
                status, createdAt, updatedAt);
    }

    /** The same room under another host: for when the host's account is gone and someone on the stage takes over. */
    public VoiceRoom withHost(UUID newHostId) {
        return new VoiceRoom(id, title, topic, newHostId, targetLanguage, nativeLanguage, maxStageSeats, status, createdAt, updatedAt);
    }

    public VoiceRoom close() {
        return new VoiceRoom(id, title, topic, hostId, targetLanguage, nativeLanguage, maxStageSeats,
                VoiceRoomStatus.CLOSED, createdAt, updatedAt);
    }

    public boolean isHost(UUID userId) {
        return hostId.equals(userId);
    }

    /** What a user is in this room: the host, a speaker on the stage, or a listener. */
    public ParticipantRole roleOf(UUID userId, boolean onStage) {
        if (isHost(userId)) {
            return ParticipantRole.HOST;
        }
        return onStage ? ParticipantRole.SPEAKER : ParticipantRole.LISTENER;
    }
}
