package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import io.micronaut.data.model.Pageable;

import java.util.List;

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

    /** Active rooms, newest first. */
    List<VoiceRoom> listActive(Pageable pageable);

    /** Closes the room (host only) and notifies other modules so they drop their ephemeral state. */
    void close(UUID roomId, String requesterId);
}
