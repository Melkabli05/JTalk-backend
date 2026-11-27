package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.NetworkMode;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.dto.ApiMapper;
import com.jtalk.livecommunication.repository.PrunedTrackRepository;
import com.jtalk.livecommunication.repository.StageRepository;
import com.jtalk.livecommunication.service.StageService;
import com.jtalk.livecommunication.service.VoiceRoomService;
import com.jtalk.livecommunication.service.RoomAccessValidator;
import com.jtalk.livecommunication.config.ProblemType;
import com.jtalk.livecommunication.integration.LiveKitGateway;
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
        access.requireInRoom(roomId, userId);
        access.requireModeratorOrHost(room, userId, "claim a stage seat directly");
        if (!stage.tryClaimStageSeat(roomId, userId, room.maxStageSeats())) {
            throw ProblemType.STAGE_FULL.because("All " + room.maxStageSeats() + " stage seats are taken");
        }
        stage.clearUserMuteStates(roomId, userId);
        gateway.setPublishPermission(roomId, userId, true);
        events.announceStage(roomId, RoomEventType.STAGE_UPDATED, null);
        return rooms.sessionFor(room, userId, true, !room.isHost(userId));
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
        stage.clearUserMuteStates(roomId, userId);
        stage.releaseSeatOnly(roomId, userId);
        events.announceStage(roomId, RoomEventType.STAGE_UPDATED, null);
    }

    @Override
    public void raiseHand(UUID roomId, UUID userId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireInRoom(roomId, userId);
        if (room.isHost(userId)) {
            throw ProblemType.CANNOT_RAISE_HAND.because("Hosts and moderators join the stage directly without raising a hand");
        }
        int position = stage.raiseHand(roomId, userId);
        if (position == -2) {
            throw ProblemType.CANNOT_RAISE_HAND.because("Hosts and moderators join the stage directly without raising a hand");
        }
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
        access.requireModeratorOrHost(room, hostId, "promote listeners to the stage");
        if (!stage.tryClaimStageSeat(roomId, targetUserId, room.maxStageSeats())) {
            throw ProblemType.STAGE_FULL.because("All " + room.maxStageSeats() + " stage seats are taken");
        }
        stage.clearUserMuteStates(roomId, targetUserId);
        gateway.setPublishPermission(roomId, targetUserId, true);
        events.announceStage(roomId, RoomEventType.STAGE_UPDATED, targetUserId);
        log.info("Moderator/Host {} promoted user {} to stage in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void muteSpeaker(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireCanModerateTarget(room, hostId, targetUserId, "mute speakers");
        stage.muteByHost(roomId, targetUserId);
        gateway.muteMicrophone(roomId, targetUserId);
        gateway.setPublishPermission(roomId, targetUserId, false);
        log.info("Moderator/Host {} muted speaker {} in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void demoteToListener(UUID roomId, UUID hostId, UUID targetUserId) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireCanModerateTarget(room, hostId, targetUserId, "demote speakers");
        leaveStageSeat(roomId, targetUserId);
        log.info("Moderator/Host {} demoted speaker {} to listener in room {}", hostId, targetUserId, roomId);
    }

    @Override
    public void setModerator(UUID roomId, UUID hostId, UUID targetUserId, boolean moderator) {
        VoiceRoom room = rooms.getRequiredActiveRoom(roomId);
        access.requireHost(room, hostId, "manage moderators");
        access.requireInRoom(roomId, targetUserId);
        if (room.isHost(targetUserId)) {
            throw ProblemType.HOST_CANNOT_LEAVE_STAGE.because("The host already has full room permissions");
        }
        if (moderator && !stage.isModerator(roomId, targetUserId)
                && stage.moderators(roomId).size() >= com.jtalk.livecommunication.config.CommunicationConstants.MAX_MODERATORS) {
            throw ProblemType.MODERATOR_LIMIT_REACHED.because("A room can have at most "
                    + com.jtalk.livecommunication.config.CommunicationConstants.MAX_MODERATORS + " moderators");
        }
        boolean changed = moderator
                ? stage.addModerator(roomId, targetUserId)
                : stage.removeModerator(roomId, targetUserId);
        if (changed) {
            events.announce(roomId, moderator ? RoomEventType.MODERATOR_ASSIGNED : RoomEventType.MODERATOR_REVOKED, targetUserId);
        }
    }

    @Override
    public void setSelfMuted(UUID roomId, UUID userId, boolean muted) {
        rooms.getRequiredActiveRoom(roomId);
        if (!stage.isOnStage(roomId, userId)) {
            throw ProblemType.NOT_IN_ROOM.because("Only seated speakers can update microphone mute state.");
        }
        stage.setSelfMuted(roomId, userId, muted);
    }

    @Override
    public void updateNetworkMode(UUID roomId, UUID userId, NetworkModeRequest request) {
        rooms.getRequiredActiveRoom(roomId);
        access.requireInRoom(roomId, userId);
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

}
