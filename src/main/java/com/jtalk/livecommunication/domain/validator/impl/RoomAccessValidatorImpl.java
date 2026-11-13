package com.jtalk.livecommunication.domain.validator.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import com.jtalk.livecommunication.domain.validator.RoomAccessValidator;
import com.jtalk.livecommunication.exception.ProblemType;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class RoomAccessValidatorImpl implements RoomAccessValidator {

    private final PresenceRepository presence;

    @Override
    public void requireHost(VoiceRoom room, UUID userId, String action) {
        if (!room.isHost(userId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Only the room host can " + action);
        }
    }

    @Override
    public void requireCanKick(VoiceRoom room, UUID requesterId, UUID targetId) {
        requireHost(room, requesterId, "kick participants");
        if (room.isHost(targetId)) {
            throw ProblemType.CANNOT_KICK_HOST.because("The host cannot be kicked from their own room");
        }
    }

    @Override
    public void requireCanLeaveStage(VoiceRoom room, UUID userId) {
        if (room.isHost(userId)) {
            throw ProblemType.HOST_CANNOT_LEAVE_STAGE.because("The host keeps their stage seat");
        }
    }

    @Override
    public void requireNotBanned(UUID roomId, UUID userId) {
        requireAdmitted(!presence.isBanned(roomId, userId), userId);
    }

    @Override
    public void requireAdmitted(boolean admitted, UUID userId) {
        if (!admitted) {
            throw ProblemType.KICKED_FROM_ROOM.because(userId + " was removed from this room by the host");
        }
    }
}
