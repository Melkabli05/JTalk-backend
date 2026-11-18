package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.NetworkMode;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.dto.ApiMapper;
import com.jtalk.livecommunication.repository.PrunedTrackRepository;
import com.jtalk.livecommunication.repository.StageRepository;
import com.jtalk.livecommunication.service.StageService;
import com.jtalk.livecommunication.service.VoiceRoomService;
import com.jtalk.livecommunication.service.RoomAccessValidator;
import com.jtalk.livecommunication.config.ProblemType;
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


    private final VoiceRoomService rooms;
    private final StageRepository stage;
    private final PrunedTrackRepository prunedTracks;
    private final LiveKitGateway gateway;
    private final ApiMapper mapper;
    private final RoomAccessValidator access;
    private final RoomEvents events;

    @Override
    public RoomSessionResponse claimStageSeat(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        if (!stage.tryClaimStageSeat(roomId, userId, room.maxStageSeats())) {
            throw ProblemType.STAGE_FULL.because("All " + room.maxStageSeats() + " stage seats are taken");
        }
        gateway.setPublishPermission(roomId, userId, true);
        events.announceStage(roomId, RoomEventType.STAGE_UPDATED, null);
        return new RoomSessionResponse(rooms.toResponse(room), gateway.serverUrl(), gateway.generateToken(roomId, userId, true),
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
        events.announceStage(roomId, RoomEventType.STAGE_UPDATED, null);
    }

    @Override
    public void raiseHand(UUID roomId, UUID userId) {
        rooms.getRequiredActiveRoom(roomId);
        int position = stage.raiseHand(roomId, userId);
        if (position < 0) {
            throw ProblemType.ALREADY_ON_STAGE.because(userId + " is already on stage");
        }
        if (position > 0) {
            events.announceStage(roomId, RoomEventType.HAND_RAISED, userId);
        }
    }

    @Override
    public void lowerHand(UUID roomId, UUID userId) {
        rooms.getRequiredActiveRoom(roomId);
        if (stage.removeHandRaise(roomId, userId)) {
            events.announceStage(roomId, RoomEventType.HAND_LOWERED, userId);
        }
    }

    @Override
    public void promoteToStage(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireHost(room, hostId, "promote listeners to the stage");
        if (!stage.tryClaimStageSeat(roomId, targetUserId, room.maxStageSeats())) {
            throw ProblemType.STAGE_FULL.because("All " + room.maxStageSeats() + " stage seats are taken");
        }
        gateway.setPublishPermission(roomId, targetUserId, true);
        events.announceStage(roomId, RoomEventType.STAGE_UPDATED, targetUserId);
        log.info("Host {} promoted user {} to stage in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void muteSpeaker(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireHost(room, hostId, "mute speakers");
        gateway.muteMicrophone(roomId, targetUserId);
        log.info("Host {} muted speaker {} in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void demoteToListener(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireHost(room, hostId, "demote speakers");
        leaveStageSeat(roomId, targetUserId);
        log.info("Host {} demoted speaker {} to listener in room {}", hostId, targetUserId, roomId);
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
