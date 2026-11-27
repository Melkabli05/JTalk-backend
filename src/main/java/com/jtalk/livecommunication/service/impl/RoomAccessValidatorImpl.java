package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.service.RoomAccessValidator;
import com.jtalk.livecommunication.config.ProblemType;
import com.jtalk.livecommunication.repository.PresenceRepository;
import com.jtalk.livecommunication.repository.StageRepository;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class RoomAccessValidatorImpl implements RoomAccessValidator {

    private final PresenceRepository presence;
    private final StageRepository stage;

    @Override
    public void requireInRoom(UUID roomId, UUID userId) {
        if (!presence.isPresent(roomId, userId)) {
            throw ProblemType.NOT_IN_ROOM.because(userId + " has not joined room " + roomId);
        }
    }

    @Override
    public void requireHost(VoiceRoom room, UUID userId, String action) {
        if (!room.isHost(userId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Only the room host can " + action);
        }
    }

    @Override
    public void requireModeratorOrHost(VoiceRoom room, UUID userId, String action) {
        if (!room.isHost(userId) && !stage.isModerator(room.id(), userId)) {
            throw ProblemType.NOT_ROOM_MODERATOR.because("Only the room host or a moderator can " + action);
        }
    }

    @Override
    public void requireCanModerateTarget(VoiceRoom room, UUID requesterId, UUID targetId, String action) {
        requireModeratorOrHost(room, requesterId, action);
        if (room.isHost(targetId)) {
            throw ProblemType.HOST_CANNOT_LEAVE_STAGE.because("The host keeps their stage seat");
        }
        if (!room.isHost(requesterId) && stage.isModerator(room.id(), targetId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Moderators cannot act on other moderators");
        }
    }

    @Override
    public void requireCanKick(VoiceRoom room, UUID requesterId, UUID targetId) {
        requireModeratorOrHost(room, requesterId, "kick participants");
        if (room.isHost(targetId)) {
            throw ProblemType.CANNOT_KICK_HOST.because("The host cannot be kicked from their own room");
        }
        if (!room.isHost(requesterId) && stage.isModerator(room.id(), targetId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Moderators cannot kick other moderators");
        }
    }

    @Override
    public void requireCanLeaveStage(VoiceRoom room, UUID userId) {
        if (room.isHost(userId)) {
            throw ProblemType.HOST_CANNOT_LEAVE_STAGE.because("The host keeps their stage seat");
        }
    }

}
