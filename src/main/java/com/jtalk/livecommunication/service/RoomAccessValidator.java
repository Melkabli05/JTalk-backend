package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;

import java.util.UUID;

/**
 * Who may do what in a room. Every method either returns or throws the matching {@code ProblemType}; none of them
 * changes anything. They work on a room the service has already loaded, so a validator never calls back into a service.
 */
public interface RoomAccessValidator {

    /**
     * @param action completes the message "Only the room host can ...", e.g. {@code "close this room"}
     * @throws io.micronaut.http.exceptions.HttpStatusException 403 {@code not-room-host} unless the user hosts the room
     */
    void requireHost(VoiceRoom room, UUID userId, String action);

    /** The host may remove anyone but themselves: 403 {@code not-room-host}, or 422 {@code cannot-kick-host}. */
    void requireCanKick(VoiceRoom room, UUID requesterId, UUID targetId);

    /** The host keeps their stage seat for the whole life of the room: 422 {@code host-cannot-leave-stage}. */
    void requireCanLeaveStage(VoiceRoom room, UUID userId);

    /** A user the host has removed may not come back: 403 {@code kicked-from-room}. */
    void requireNotBanned(UUID roomId, UUID userId);

    /**
     * For callers that learn about the ban as part of another Redis round trip (the heartbeat): 403
     * {@code kicked-from-room} when {@code admitted} is false.
     */
    void requireAdmitted(boolean admitted, UUID userId);
}
