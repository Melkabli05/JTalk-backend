package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.domain.dto.request.WhisperRequest;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.NetworkMode;
import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.domain.model.WhisperSignal;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import com.jtalk.livecommunication.domain.repository.PrunedTrackRepository;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import com.jtalk.livecommunication.domain.repository.WhisperRepository;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.exception.ProblemType;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor
class StageServiceImpl implements StageService {

    private static final String WHISPER_TOPIC = "whisper";

    private final VoiceRoomService rooms;
    private final StageRepository stage;
    private final PresenceRepository presence;
    private final WhisperRepository whispers;
    private final PrunedTrackRepository prunedTracks;
    private final LiveKitGateway gateway;
    private final ApiMapper mapper;
    private final Clock clock;

    @Override
    public RoomSessionResponse claimStageSeat(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (!stage.tryClaimStageSeat(roomId, userId, room.maxStageSeats())) {
            throw ProblemType.STAGE_FULL.because("All " + room.maxStageSeats() + " stage seats are taken");
        }
        gateway.setPublishPermission(roomId, userId, true);
        announce(RoomEventType.STAGE_UPDATED, roomId, null);
        return new RoomSessionResponse(rooms.toResponse(room), gateway.generateToken(roomId, userId, true),
                room.isHost(userId) ? ParticipantRole.HOST : ParticipantRole.SPEAKER, true);
    }

    @Override
    public void leaveStageSeat(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (room.isHost(userId)) {
            throw ProblemType.HOST_CANNOT_LEAVE_STAGE.because("The host keeps their stage seat");
        }
        gateway.setPublishPermission(roomId, userId, false);
        stage.releaseAndPromoteNext(roomId, userId).ifPresent(promoted -> gateway.setPublishPermission(roomId, promoted, true));
        announce(RoomEventType.STAGE_UPDATED, roomId, null);
    }

    @Override
    public void raiseHand(UUID roomId, UUID userId) {
        rooms.getRequiredActiveRoom(roomId);
        if (stage.raiseHand(roomId, userId) < 0) {
            throw ProblemType.ALREADY_ON_STAGE.because(userId + " is already on stage");
        }
        announce(RoomEventType.HAND_RAISED, roomId, userId);
    }

    @Override
    public void lowerHand(UUID roomId, UUID userId) {
        rooms.getRequiredActiveRoom(roomId);
        stage.removeHandRaise(roomId, userId);
        announce(RoomEventType.HAND_LOWERED, roomId, userId);
    }

    @Override
    public void promoteToStage(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (!room.isHost(hostId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Only the room host can promote listeners to the stage");
        }
        if (!stage.tryClaimStageSeat(roomId, targetUserId, room.maxStageSeats())) {
            throw ProblemType.STAGE_FULL.because("All " + room.maxStageSeats() + " stage seats are taken");
        }
        gateway.setPublishPermission(roomId, targetUserId, true);
        announce(RoomEventType.STAGE_UPDATED, roomId, targetUserId);
        log.info("Host {} promoted user {} to stage in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void demoteToListener(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (!room.isHost(hostId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Only the room host can demote speakers");
        }
        leaveStageSeat(roomId, targetUserId);
        log.info("Host {} demoted speaker {} to listener in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void startWhisper(UUID roomId, UUID speakerId, WhisperRequest request) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        UUID targetId = request.targetUserId();
        if (!room.allowWhisper()) {
            throw ProblemType.WHISPER_NOT_PERMITTED.because("Whisper side-channels are disabled in this room");
        }
        if (!stage.isOnStage(roomId, speakerId)) {
            throw ProblemType.WHISPER_NOT_PERMITTED.because("Only stage speakers can whisper");
        }
        if (speakerId.equals(targetId)) {
            throw ProblemType.WHISPER_TO_SELF.because("You cannot whisper to yourself");
        }
        if (!presence.isPresent(roomId, targetId)) {
            throw ProblemType.WHISPER_TARGET_NOT_PRESENT.because(targetId + " is not in the room");
        }
        stopWhisper(roomId, speakerId);   // one whisper at a time
        whispers.start(roomId, speakerId, targetId);
        for (UUID side : List.of(speakerId, targetId)) {
            String token = gateway.generateWhisperToken(roomId, speakerId, targetId, side);   // each side gets only its own
            gateway.sendData(roomId, WHISPER_TOPIC, new WhisperSignal("start", speakerId, targetId, token), List.of(side));
        }
    }

    @Override
    public void stopWhisper(UUID roomId, UUID speakerId) {
        whispers.find(roomId, speakerId).ifPresent(targetId -> {
            whispers.stop(roomId, speakerId);
            gateway.deleteWhisperRoom(roomId, speakerId, targetId);
            for (UUID side : List.of(speakerId, targetId)) {
                gateway.sendData(roomId, WHISPER_TOPIC, new WhisperSignal("stop", speakerId, targetId, null), List.of(side));
            }
        });
    }

    @Override
    public void updateNetworkMode(UUID roomId, UUID userId, NetworkModeRequest request) {
        rooms.getRequiredActiveRoom(roomId);
        if (request.mode() == NetworkMode.LOW_BANDWIDTH) {
            prunedTracks.add(roomId, userId, request.secondaryTrackSidsToDrop());
            gateway.updateSubscriptions(roomId, userId, request.secondaryTrackSidsToDrop(), false);
            gateway.updateSubscriptions(roomId, userId, request.primaryTrackSidsToKeep(), true);
        } else {
            Set<String> restore = new LinkedHashSet<>(prunedTracks.take(roomId, userId));
            restore.addAll(request.secondaryTrackSidsToDrop());
            gateway.updateSubscriptions(roomId, userId, List.copyOf(restore), true);
        }
    }

    @Override
    public StageResponse getStage(UUID roomId) {
        rooms.getRequiredActiveRoom(roomId);
        return mapper.toResponse(stage.snapshot(roomId));
    }

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        stage.clear(event.roomId());
        prunedTracks.clear(event.roomId());
    }

    private void announce(RoomEventType type, UUID roomId, UUID targetUserId) {
        gateway.broadcastRoomEvent(roomId, new RoomRealtimeEvent(type, roomId, targetUserId, clock.instant()));
    }
}
