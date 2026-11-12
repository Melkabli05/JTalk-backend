package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import java.util.UUID;

/** Room lifecycle and discovery. */
public interface VoiceRoomService {

    VoiceRoom create(String name, String hostId, Integer maxSpeakers);

    /** Throws a 404 problem if the room does not exist. */
    VoiceRoom get(UUID roomId);

    /** Like {@link #get} but fails with 409 if the room is closed. */
    VoiceRoom getActive(UUID roomId);

    /** Like {@link #getActive} but 403 unless {@code userId} is the host. */
    VoiceRoom getActiveAsHost(UUID roomId, String userId);

    /**
     * Host-only partial update of an active room; a null argument keeps the current value. Lowering the seat
     * count below the number of speakers does not evict anyone: new seats are simply unavailable until enough free up.
     */
    VoiceRoom update(UUID roomId, String requesterId, @Nullable String name, @Nullable Integer maxSpeakers);

    /** Real-time numbers for each of the rooms (one batched Redis round trip); every id gets an entry. */
    Map<UUID, RoomLiveStats> liveStats(Collection<UUID> roomIds);

    /** Active rooms, newest first. */
    List<VoiceRoom> listActive(Pageable pageable);

    /** Closes the room (host only) and notifies other modules so they drop their ephemeral state. */
    void close(UUID roomId, String requesterId);
}
