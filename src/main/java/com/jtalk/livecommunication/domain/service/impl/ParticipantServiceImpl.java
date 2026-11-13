package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import com.jtalk.livecommunication.domain.model.Participant;
import com.jtalk.livecommunication.domain.repository.LeaseRepository;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.validator.RoomAccessValidator;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor
class ParticipantServiceImpl implements ParticipantService {

    private final VoiceRoomService rooms;
    private final StageService stages;
    private final StageRepository stage;
    private final PresenceRepository presence;
    private final LeaseRepository leases;
    private final LiveKitGateway gateway;
    private final ApiMapper mapper;
    private final RoomAccessValidator access;

    @Override
    public RoomSessionResponse joinRoom(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireNotBanned(roomId, userId);
        boolean onStage = stage.isOnStage(roomId, userId);
        if (!onStage && room.isHost(userId)) {
            onStage = stage.tryClaimStageSeat(roomId, userId, room.maxStageSeats());   // the host's seat is permanent
        }
        if (onStage && leases.delete(roomId, userId)) {   // reconnecting within the grace lease restores the seat
            gateway.broadcastRoomEvent(roomId, RoomEventType.SPEAKER_RECONNECTED, userId);
        }
        presence.touch(roomId, userId);
        return new RoomSessionResponse(rooms.toResponse(room), gateway.generateToken(roomId, userId, onStage),
                room.roleOf(userId, onStage), onStage);
    }

    @Override
    public void leaveRoom(UUID roomId, UUID userId) {
        presence.remove(roomId, userId);
        stage.removeHandRaise(roomId, userId);
        if (!stage.isOnStage(roomId, userId) || isHost(roomId, userId)) {
            return;   // a listener leaving changes nothing on the stage; the host keeps their seat
        }
        stages.vacateSeat(roomId, userId);
    }

    @Override
    public void kickParticipant(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireCanKick(room, hostId, targetUserId);
        presence.ban(roomId, targetUserId);
        leaveRoom(roomId, targetUserId);
        gateway.removeParticipant(roomId, targetUserId);
        log.info("Host {} kicked participant {} from room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void handleMediaDisconnected(UUID roomId, UUID userId) {
        if (!isActiveRoomWithoutHost(roomId, userId)) {
            return;   // a closed room, or the host, whose seat is permanent
        }
        if (stage.isOnStage(roomId, userId)) {
            log.info("Speaker {} media dropped in room {}. Starting the stage grace lease.", userId, roomId);
            if (leases.create(roomId, userId)) {
                gateway.broadcastRoomEvent(roomId, RoomEventType.SPEAKER_RECONNECTING, userId);
            }
        } else {
            presence.remove(roomId, userId);
            stage.removeHandRaise(roomId, userId);
        }
    }

    @Override
    public void handleMediaReconnected(UUID roomId, UUID userId) {
        if (leases.delete(roomId, userId)) {
            gateway.broadcastRoomEvent(roomId, RoomEventType.SPEAKER_RECONNECTED, userId);
        }
    }

    @Override
    public void handleDisconnectLeaseExpired(UUID roomId, UUID expiredUserId) {
        if (!isActiveRoomWithoutHost(roomId, expiredUserId)) {
            return;
        }
        log.info("Grace lease expired for speaker {} in room {}. Evicting from stage.", expiredUserId, roomId);
        stages.vacateSeat(roomId, expiredUserId);
    }

    @Override
    public void heartbeat(UUID roomId, UUID userId) {
        rooms.getRequiredActiveRoom(roomId);
        access.requireAdmitted(presence.touch(roomId, userId), userId);
    }

    @Override
    public List<ParticipantResponse> listParticipants(UUID roomId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        Set<UUID> speakers = new HashSet<>(stage.snapshot(roomId).speakers());
        List<UUID> present = presence.findPresent(roomId);
        Set<UUID> reconnecting = leases.usersWithLease(roomId, present);
        return mapper.toParticipantResponses(present.stream()
                .map(userId -> new Participant(userId, room.roleOf(userId, speakers.contains(userId)),
                        reconnecting.contains(userId) ? PresenceStatus.RECONNECTING : PresenceStatus.ACTIVE))
                .toList());
    }

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        presence.clear(event.roomId());
    }

    private boolean isHost(UUID roomId, UUID userId) {
        return rooms.findActiveRoom(roomId).filter(room -> room.isHost(userId)).isPresent();
    }

    /** True if the room is still open and the user is not its host (whose seat is permanent). */
    private boolean isActiveRoomWithoutHost(UUID roomId, UUID userId) {
        return rooms.findActiveRoom(roomId).filter(room -> !room.isHost(userId)).isPresent();
    }
}
