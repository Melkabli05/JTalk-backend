package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Room lifecycle (create, update, close), active-room validation and keyset-paginated discovery. */
@Validated
public interface VoiceRoomService {

    /** Creates the room, seats the host on the stage and returns the host's session. */
    RoomSessionResponse createRoom(@NonNull UUID hostId, @Valid @NotNull CreateVoiceRoomRequest request);

    CursoredPage<VoiceRoomResponse> listActiveRooms(@Nullable String targetLanguage, int size, @Nullable Instant afterCreatedAt, @Nullable UUID afterId);

    /** The newest open rooms (at most one discovery page), read fresh: for a feed's first message, which must not miss a room that just opened or closed. */
    List<VoiceRoomResponse> openRooms();

    VoiceRoomResponse getRoom(@NonNull UUID roomId);

    /** Host-only partial update. Lowering the seat count never evicts anyone; seats just stay unavailable until freed. */
    VoiceRoomResponse updateRoom(@NonNull UUID roomId, @NonNull UUID requesterId, @Valid @NotNull UpdateVoiceRoomRequest request);

    /** Host-only. Marks the room closed; Redis state and the LiveKit room are cleaned up after the commit. */
    void closeRoom(@NonNull UUID roomId, @NonNull UUID requesterId);

    /** The ids of the open rooms the person hosts (a host runs one at a time, but a deletion must not rely on it). */
    List<UUID> activeRoomIdsHostedBy(@NonNull UUID hostId);

    /**
     * Makes {@code toUserId} the host of the room, if {@code fromHostId} still is: for when the host's account is gone and someone who was
     * on the stage takes over. Everyone is told who hosts it now. A room that has already been handed over, or closed, is left alone.
     */
    void handOverRoom(@NonNull UUID roomId, @NonNull UUID fromHostId, @NonNull UUID toUserId);

    /** The ids of the rooms that are open right now. */
    List<UUID> openRoomIds();

    /** Erases every room the person ever hosted, closed ones included: a deleted account leaves no titles or topics behind. */
    void eraseRoomsHostedBy(@NonNull UUID hostId);

    /** The room if it exists and is open; for callers (webhooks) that must not fail on a room that was just closed. */
    Optional<VoiceRoom> findActiveRoom(@NonNull UUID roomId);

    /** Shared by the other services so a missing or closed room is always the same 404. */
    VoiceRoom getRequiredActiveRoom(@NonNull UUID roomId);

    /** The room merged with its real-time numbers, without re-reading it from the database. */
    VoiceRoomResponse toResponse(@NonNull VoiceRoom room);
}
