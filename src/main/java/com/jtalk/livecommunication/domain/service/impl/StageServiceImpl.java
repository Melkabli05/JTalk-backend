package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.domain.dto.request.WhisperRequest;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.NetworkMode;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import com.jtalk.livecommunication.domain.model.WhisperSignal;
import com.jtalk.livecommunication.domain.repository.PrunedTrackRepository;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import com.jtalk.livecommunication.domain.repository.WhisperRepository;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.domain.validator.RoomAccessValidator;
import com.jtalk.livecommunication.domain.validator.WhisperValidator;
import com.jtalk.livecommunication.exception.ProblemType;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private final WhisperRepository whispers;
    private final PrunedTrackRepository prunedTracks;
    private final LiveKitGateway gateway;
    private final ApiMapper mapper;
    private final RoomAccessValidator access;
    private final WhisperValidator whisperRules;

    @Override
    public RoomSessionResponse claimStageSeat(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (!stage.tryClaimStageSeat(roomId, userId, room.maxStageSeats())) {
            throw ProblemType.STAGE_FULL.because("All " + room.maxStageSeats() + " stage seats are taken");
        }
        gateway.setPublishPermission(roomId, userId, true);
        gateway.broadcastRoomEvent(roomId, RoomEventType.STAGE_UPDATED, null);
        return new RoomSessionResponse(rooms.toResponse(room), gateway.generateToken(roomId, userId, true),
                room.roleOf(userId, true), true);
    }

    @Override
    public void leaveStageSeat(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireCanLeaveStage(room, userId);
        gateway.setPublishPermission(roomId, userId, false);
        vacateSeat(roomId, userId);
    }

    @Override
    public void vacateSeat(UUID roomId, UUID userId) {
        stage.releaseAndPromoteNext(roomId, userId).ifPresent(promoted -> {
            log.info("Seat freed in room {}: promoting queued user {} to the stage", roomId, promoted);
            gateway.setPublishPermission(roomId, promoted, true);
        });
        gateway.broadcastRoomEvent(roomId, RoomEventType.STAGE_UPDATED, null);
    }

    @Override
    public void raiseHand(UUID roomId, UUID userId) {
        rooms.getRequiredActiveRoom(roomId);
        if (stage.raiseHand(roomId, userId) < 0) {
            throw ProblemType.ALREADY_ON_STAGE.because(userId + " is already on stage");
        }
        gateway.broadcastRoomEvent(roomId, RoomEventType.HAND_RAISED, userId);
    }

    @Override
    public void lowerHand(UUID roomId, UUID userId) {
        rooms.getRequiredActiveRoom(roomId);
        stage.removeHandRaise(roomId, userId);
        gateway.broadcastRoomEvent(roomId, RoomEventType.HAND_LOWERED, userId);
    }

    @Override
    public void promoteToStage(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireHost(room, hostId, "promote listeners to the stage");
        if (!stage.tryClaimStageSeat(roomId, targetUserId, room.maxStageSeats())) {
            throw ProblemType.STAGE_FULL.because("All " + room.maxStageSeats() + " stage seats are taken");
        }
        gateway.setPublishPermission(roomId, targetUserId, true);
        gateway.broadcastRoomEvent(roomId, RoomEventType.STAGE_UPDATED, targetUserId);
        log.info("Host {} promoted user {} to stage in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void demoteToListener(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireHost(room, hostId, "demote speakers");
        leaveStageSeat(roomId, targetUserId);
        log.info("Host {} demoted speaker {} to listener in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void startWhisper(UUID roomId, UUID speakerId, WhisperRequest request) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        UUID targetId = request.targetUserId();
        whisperRules.requireAllowed(room, speakerId, targetId);
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
}
