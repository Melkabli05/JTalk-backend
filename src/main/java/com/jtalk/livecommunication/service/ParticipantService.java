package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.RoomSnapshotResponse;
import com.jtalk.livecommunication.domain.model.AudiencePage;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
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

    RoomSnapshotResponse snapshot(@NonNull UUID roomId, @NonNull UUID userId);

    /** Leaves the room, cleaning up presence, hand raise or stage seat (promoting the next hand-raiser). */
    void leaveRoom(@NonNull UUID roomId, @NonNull UUID userId);

    void leaveRoom(@NonNull UUID roomId, @NonNull UUID userId, @io.micronaut.core.annotation.Nullable UUID successorUserId);

    /**
     * Host-only moderation: removes the user (freeing their seat), disconnects them from LiveKit, revokes the tokens they
     * hold and bars them from joining the room again.
     */
    void kickParticipant(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID targetUserId);

    /** Webhook: a speaker's media dropped. Their seat is held under a grace lease; anyone else simply leaves the room. */
    void handleMediaDisconnected(@NonNull UUID roomId, @NonNull UUID userId);

    /** Webhook: the participant's media connection is up. They are present, and back within the lease if they had one. */
    void handleMediaConnected(@NonNull UUID roomId, @NonNull UUID userId);

    /**
     * Takes the person out of every open room they are in, for when their account is deleted: presence, raised hand and stage seat are
     * dropped (the next hand-raiser is promoted), their media connection is cut and the tokens they hold are revoked. Rooms they are not
     * in are not touched.
     */
    void removeFromAllRooms(@NonNull UUID userId);

    /** Who is in the room, with roles and whether they are inside a grace lease. */
    List<ParticipantResponse> listParticipants(@NonNull UUID roomId);

    AudiencePage audience(@NonNull UUID roomId, @NonNull UUID userId, @Nullable UUID cursor);
}
