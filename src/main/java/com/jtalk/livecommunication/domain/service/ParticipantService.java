package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.validation.Validated;

import java.util.List;
import java.util.UUID;

/** Room entry and exit, host moderation, presence, and the 30-second grace lease that survives network handovers. */
@Validated
public interface ParticipantService {

    /**
     * Joins an active room as a listener (or restores the seat of a speaker who is reconnecting) and returns the session
     * with a scoped LiveKit token. A user the host kicked is refused.
     */
    RoomSessionResponse joinRoom(@NonNull UUID roomId, @NonNull UUID userId);

    /** Leaves the room, cleaning up presence, hand raise or stage seat (promoting the next hand-raiser). */
    void leaveRoom(@NonNull UUID roomId, @NonNull UUID userId);

    /**
     * Host-only moderation: removes the user (freeing their seat), disconnects them from LiveKit, revokes the tokens they
     * hold and bars them from joining the room again.
     */
    void kickParticipant(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID targetUserId);

    /** Webhook: a speaker's media dropped. Their seat is held under a grace lease; anyone else simply leaves. */
    void handleMediaDisconnected(@NonNull UUID roomId, @NonNull UUID userId);

    /** Webhook: the participant is back within the lease. */
    void handleMediaReconnected(@NonNull UUID roomId, @NonNull UUID userId);

    /** The lease ran out: evicts the speaker and promotes the next hand-raiser. */
    void handleDisconnectLeaseExpired(@NonNull UUID roomId, @NonNull UUID expiredUserId);

    /** Presence heartbeat. */
    void heartbeat(@NonNull UUID roomId, @NonNull UUID userId);

    /** Who is in the room, with roles and whether they are inside a grace lease. */
    List<ParticipantResponse> listParticipants(@NonNull UUID roomId);
}
