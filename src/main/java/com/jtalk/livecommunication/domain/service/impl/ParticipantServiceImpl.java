package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import com.jtalk.livecommunication.domain.model.Participant;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.domain.repository.LeaseRepository;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.exception.ProblemType;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor
class ParticipantServiceImpl implements ParticipantService {

    private final VoiceRoomService rooms;
    private final StageRepository stage;
    private final PresenceRepository presence;
    private final LeaseRepository leases;
    private final LiveKitGateway gateway;
    private final ApiMapper mapper;
    private final Clock clock;

    @Override
    public RoomSessionResponse joinRoom(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (presence.isBanned(roomId, userId)) {
            throw ProblemType.KICKED_FROM_ROOM.because(userId + " was removed from this room by the host");
        }
        boolean onStage = stage.isOnStage(roomId, userId);
        if (!onStage && room.isHost(userId)) {
            onStage = stage.tryClaimStageSeat(roomId, userId, room.maxStageSeats());   // the host's seat is permanent
        }
        if (onStage && leases.delete(roomId, userId)) {   // reconnecting within the grace lease restores the seat
            announce(RoomEventType.SPEAKER_RECONNECTED, roomId, userId);
        }
        presence.touch(roomId, userId);
        return new RoomSessionResponse(rooms.toResponse(room), gateway.generateToken(roomId, userId, onStage),
                roleOf(room, userId, onStage), onStage);
    }

    @Override
    public void leaveRoom(UUID roomId, UUID userId) {
        presence.remove(roomId, userId);
        stage.removeHandRaise(roomId, userId);
        if (isHost(roomId, userId) || !stage.isOnStage(roomId, userId)) {
            return;   // the host keeps their seat; a listener leaving changes nothing on the stage
        }
        stage.releaseAndPromoteNext(roomId, userId).ifPresent(promoted -> gateway.setPublishPermission(roomId, promoted, true));
        announce(RoomEventType.STAGE_UPDATED, roomId, null);
    }

    @Override
    public void kickParticipant(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (!room.isHost(hostId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Only the room host can kick participants");
        }
        if (room.isHost(targetUserId)) {
            throw ProblemType.CANNOT_KICK_HOST.because("The host cannot be kicked from their own room");
        }
        presence.ban(roomId, targetUserId);
        leaveRoom(roomId, targetUserId);
        gateway.removeParticipant(roomId, targetUserId);
        log.info("Host {} kicked participant {} from room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void handleMediaDisconnected(UUID roomId, UUID userId) {
        Optional<VoiceRoom> room = rooms.findActiveRoom(roomId);
        if (room.isEmpty() || room.get().isHost(userId)) {
            return;   // a closed room, or the host, whose seat is permanent
        }
        if (stage.isOnStage(roomId, userId)) {
            log.info("Speaker {} media dropped in room {}. Starting the stage grace lease.", userId, roomId);
            if (leases.create(roomId, userId)) {
                announce(RoomEventType.SPEAKER_RECONNECTING, roomId, userId);
            }
        } else {
            presence.remove(roomId, userId);
            stage.removeHandRaise(roomId, userId);
        }
    }

    @Override
    public void handleMediaReconnected(UUID roomId, UUID userId) {
        if (leases.delete(roomId, userId)) {
            announce(RoomEventType.SPEAKER_RECONNECTED, roomId, userId);
        }
    }

    @Override
    public void handleDisconnectLeaseExpired(UUID roomId, UUID expiredUserId) {
        if (rooms.findActiveRoom(roomId).filter(room -> !room.isHost(expiredUserId)).isEmpty()) {
            return;
        }
        log.info("Grace lease expired for speaker {} in room {}. Evicting from stage.", expiredUserId, roomId);
        stage.releaseAndPromoteNext(roomId, expiredUserId).ifPresent(promoted -> {
            log.info("Auto-promoting queued user {} to stage in room {}", promoted, roomId);
            gateway.setPublishPermission(roomId, promoted, true);
        });
        announce(RoomEventType.STAGE_UPDATED, roomId, null);
    }

    @Override
    public void heartbeat(UUID roomId, UUID userId) {
        rooms.getRequiredActiveRoom(roomId);
        if (!presence.touch(roomId, userId)) {
            throw ProblemType.KICKED_FROM_ROOM.because(userId + " was removed from this room by the host");
        }
    }

    @Override
    public List<ParticipantResponse> listParticipants(UUID roomId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        Set<UUID> speakers = new HashSet<>(stage.snapshot(roomId).speakers());
        List<UUID> present = presence.findPresent(roomId);
        Set<UUID> reconnecting = leases.usersWithLease(roomId, present);
        return mapper.toParticipantResponses(present.stream()
                .map(userId -> new Participant(userId, roleOf(room, userId, speakers.contains(userId)),
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

    private void announce(RoomEventType type, UUID roomId, UUID targetUserId) {
        gateway.broadcastRoomEvent(roomId, new RoomRealtimeEvent(type, roomId, targetUserId, clock.instant()));
    }

    private static ParticipantRole roleOf(VoiceRoom room, UUID userId, boolean onStage) {
        if (room.isHost(userId)) {
            return ParticipantRole.HOST;
        }
        return onStage ? ParticipantRole.SPEAKER : ParticipantRole.LISTENER;
    }
}
