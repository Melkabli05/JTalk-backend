package com.jtalk.livecommunication.domain.entity;

import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.AutoPopulated;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent room metadata. Real-time state (who is on stage, who is present) is NOT stored here; it
 * lives in Redis and is owned by the stage and participant modules.
 *
 * @param maxSpeakers number of stage seats for approved speakers. The host always holds publish rights
 *                    and does not consume a seat.
 */
@MappedEntity("voice_rooms")
public record VoiceRoom(
        @Id @AutoPopulated UUID id,
        String name,
        String hostId,
        int maxSpeakers,
        VoiceRoomStatus status,
        @DateCreated Instant createdAt,
        @Nullable Instant closedAt) {

    public static final int NAME_MAX_LENGTH = 120;
    public static final int MIN_SPEAKERS = 1;
    public static final int MAX_SPEAKERS = 12;
    public static final int DEFAULT_SPEAKERS = 8;

    /** A new, unsaved room: the id and creation time are filled in by Micronaut Data when it is inserted. */
    public static VoiceRoom open(String name, String hostId, @Nullable Integer maxSpeakers) {
        return new VoiceRoom(null, name.strip(), hostId,
                maxSpeakers == null ? DEFAULT_SPEAKERS : maxSpeakers, VoiceRoomStatus.ACTIVE, null, null);
    }

    /** Applies a partial update; a null argument keeps the current value. */
    public VoiceRoom withSettings(@Nullable String newName, @Nullable Integer newMaxSpeakers) {
        return new VoiceRoom(id, newName == null ? name : newName.strip(), hostId,
                newMaxSpeakers == null ? maxSpeakers : newMaxSpeakers, status, createdAt, closedAt);
    }

    public VoiceRoom close(Instant now) {
        return new VoiceRoom(id, name, hostId, maxSpeakers, VoiceRoomStatus.CLOSED, createdAt, now);
    }

    public boolean isActive() {
        return status == VoiceRoomStatus.ACTIVE;
    }

    public boolean isHost(String userId) {
        return hostId.equals(userId);
    }
}
