package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import com.jtalk.livecommunication.domain.event.LeaseExpired;
import com.jtalk.livecommunication.dto.ApiMapper;
import com.jtalk.livecommunication.domain.model.Participant;
import com.jtalk.livecommunication.domain.model.AudiencePage;
import com.jtalk.livecommunication.config.ProblemType;
import com.jtalk.livecommunication.repository.LeaseRepository;
import com.jtalk.livecommunication.repository.PresenceRepository;
import com.jtalk.livecommunication.repository.RosterRepository;
import com.jtalk.livecommunication.repository.RoomJoinRepository;
import com.jtalk.livecommunication.repository.StageRepository;
import com.jtalk.livecommunication.service.ParticipantService;
import com.jtalk.livecommunication.service.StageService;
import com.jtalk.livecommunication.service.RoomAccessValidator;
import com.jtalk.livecommunication.service.VoiceRoomService;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private final StageService stages;
    private final StageRepository stage;
    private final PresenceRepository presence;
    private final LeaseRepository leases;
    private final RosterRepository rosterRepository;
    private final RoomJoinRepository roomJoin;
    private final LiveKitGateway gateway;
    private final ApiMapper mapper;
    private final RoomAccessValidator access;
    private final RoomEvents events;

    @Override
    public RoomSessionResponse joinRoom(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        var join = roomJoin.enter(roomId, userId, room.isHost(userId), room.maxStageSeats())
                .orElseThrow(() -> ProblemType.KICKED_FROM_ROOM.because(userId + " was removed from this room by the host"));
        if (join.restoredSeat()) {
            events.announce(roomId, RoomEventType.SPEAKER_RECONNECTED, userId);
        }
        events.userJoined(roomId, userId);
        return new RoomSessionResponse(mapper.toResponse(room, join.stats()), gateway.serverUrl(),
                gateway.generateToken(roomId, userId, join.onStage(), join.joiningProfile()),
                room.roleOf(userId, join.onStage()), join.onStage(), join.snapshot().chat().muted(), join.snapshot());
    }

    @Override
    public void leaveRoom(UUID roomId, UUID userId) {
        RosterRepository.Departure left = rosterRepository.leave(roomId, userId);   // presence and hand dropped, seat looked up: one round trip
        if (left.wasPresent()) {
            events.userLeft(roomId, userId);
        }
        if (!left.onStage() || isHost(roomId, userId)) {
            return;   // a listener leaving changes nothing on the stage; the host keeps their seat
        }
        stages.vacateSeat(roomId, userId);
    }

    @Override
    public void removeFromAllRooms(UUID userId) {
        for (UUID roomId : rooms.openRoomIds()) {
            RosterRepository.Departure left = rosterRepository.leave(roomId, userId);
            if (!left.wasPresent() && !left.onStage()) {
                continue;
            }
            if (left.wasPresent()) {
                events.userLeft(roomId, userId);
            }
            if (left.onStage() && !isHost(roomId, userId)) {
                stages.vacateSeat(roomId, userId);
            }
            gateway.removeParticipant(roomId, userId);
            log.info("Removed deleted account {} from room {}", userId, roomId);
        }
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
        Optional<VoiceRoom> room = rooms.findActiveRoom(roomId);
        if (room.isEmpty()) {
            return;
        }
        if (room.get().isHost(userId)) {
            dropPresence(roomId, userId);
        } else if (stage.isOnStage(roomId, userId)) {
            log.info("Speaker {} media dropped in room {}. Starting the stage grace lease.", userId, roomId);
            if (leases.create(roomId, userId)) {
                events.announce(roomId, RoomEventType.SPEAKER_RECONNECTING, userId);
            }
        } else {
            dropPresence(roomId, userId);
            stage.removeHandRaise(roomId, userId);
        }
    }

    @Override
    public void handleMediaConnected(UUID roomId, UUID userId) {
        if (rooms.findActiveRoom(roomId).isEmpty() || presence.isBanned(roomId, userId)) {
            return;
        }
        if (presence.connected(roomId, userId)) {
            events.userJoined(roomId, userId);
        }
        if (leases.delete(roomId, userId)) {
            events.announce(roomId, RoomEventType.SPEAKER_RECONNECTED, userId);
        }
    }

    /** A speaker's grace lease ran out (reported by the Redis expiry listener): the seat goes back to the stage. */
    @EventListener
    void onLeaseExpired(LeaseExpired event) {
        if (!isActiveRoomWithoutHost(event.roomId(), event.userId())) {
            return;
        }
        log.info("Grace lease expired for speaker {} in room {}. Evicting from stage.", event.userId(), event.roomId());
        stages.vacateSeat(event.roomId(), event.userId());
        dropPresence(event.roomId(), event.userId());
    }

    @Override
    public List<ParticipantResponse> listParticipants(UUID roomId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        RosterRepository.Roster roster = rosterRepository.read(roomId);
        Set<UUID> speakers = new HashSet<>(roster.speakers());
        return mapper.toParticipantResponses(roster.present().stream()
                .map(userId -> new Participant(userId, room.roleOf(userId, speakers.contains(userId)),
                        roster.reconnecting().contains(userId) ? PresenceStatus.RECONNECTING : PresenceStatus.ACTIVE))
                .toList());
    }

    @Override
    public AudiencePage audience(UUID roomId, UUID userId, UUID cursor) {
        rooms.getRequiredActiveRoom(roomId);
        access.requireInRoom(roomId, userId);
        return roomJoin.audience(roomId, cursor);
    }

    private void dropPresence(UUID roomId, UUID userId) {
        if (presence.remove(roomId, userId)) {
            events.userLeft(roomId, userId);
        }
    }

    private boolean isHost(UUID roomId, UUID userId) {
        return rooms.findActiveRoom(roomId).filter(room -> room.isHost(userId)).isPresent();
    }

    /** True if the room is still open and the user is not its host (whose seat is permanent). */
    private boolean isActiveRoomWithoutHost(UUID roomId, UUID userId) {
        return rooms.findActiveRoom(roomId).filter(room -> !room.isHost(userId)).isPresent();
    }
}
