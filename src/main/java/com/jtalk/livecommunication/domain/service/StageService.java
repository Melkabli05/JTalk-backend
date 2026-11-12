package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.model.StageState;

import java.util.Optional;
import java.util.UUID;

/** Speakers, stage seats and the hand-raise queue. */
public interface StageService {

    /** Adds the user to the hand-raise queue. Returns the 1-based queue position. */
    int raiseHand(UUID roomId, String userId);

    void lowerHand(UUID roomId, String userId);

    /**
     * Host approves a queued user: seat them, then grant publish rights on the media server. If the media
     * server call fails the seat is released again so stage and media permissions never diverge.
     */
    void approveSpeaker(UUID roomId, String hostId, String userId);

    /**
     * A speaker steps down, or the host removes a speaker. Idempotent: the publish permission is revoked
     * even if the user was already off stage, so a retry after a media server failure converges.
     */
    void removeSpeaker(UUID roomId, String requesterId, String userId);

    StageState getStage(UUID roomId);

    boolean isOnStage(UUID roomId, String userId);

    /** Drops every trace of a user who left the room. Local state only; they are no longer connected. */
    void clearUser(UUID roomId, String userId);

    /** Frees the user's seat without touching the media server (they are already gone). */
    void releaseSeat(UUID roomId, String userId);

    /**
     * Seats the longest-waiting hand-raiser if a seat is free and grants them publish rights.
     * Returns who was seated; empty if nobody was waiting, the stage is full or the room is closed.
     */
    Optional<String> promoteNext(UUID roomId);
}
