package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.StageResponse;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Stage seats, the hand-raise queue, host promotions and demotions and low-bandwidth mode. */
@Validated
public interface StageService {

    /** Atomically takes a free seat (leaving the hand-raise queue) and upgrades publish rights. */
    RoomSessionResponse claimStageSeat(@NonNull UUID roomId, @NonNull UUID userId);

    /** Steps down to the audience and, atomically, seats the longest-waiting hand-raiser. The host keeps their seat. */
    void leaveStageSeat(@NonNull UUID roomId, @NonNull UUID userId);

    /**
     * Frees a user's seat, seats the longest-waiting hand-raiser in their place and tells the room. The one place that
     * rule lives, for callers that have already decided the user must go: they left the room, or their grace lease
     * ran out after a disconnect. No permission check; the user's own publish rights are not touched.
     */
    void vacateSeat(@NonNull UUID roomId, @NonNull UUID userId);

    void raiseHand(@NonNull UUID roomId, @NonNull UUID userId);

    void lowerHand(@NonNull UUID roomId, @NonNull UUID userId);

    /** Host-only: puts a user on the stage (taking them off the queue). */
    void promoteToStage(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID targetUserId);

    /** Host-only: mutes a speaker's microphone. They can unmute themselves. */
    void muteSpeaker(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID targetUserId);

    /** Host-only: steps a speaker down to the audience. */
    void demoteToListener(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID targetUserId);

    /**
     * {@code LOW_BANDWIDTH} unsubscribes the user from their secondary tracks and remembers them; {@code OPTIMAL} restores
     * those (plus any the request lists).
     */
    void updateNetworkMode(@NonNull UUID roomId, @NonNull UUID userId, @Valid @NotNull NetworkModeRequest request);

    StageResponse getStage(@NonNull UUID roomId);
}
