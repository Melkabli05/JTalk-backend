package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.service.RoomAccessValidator;
import com.jtalk.livecommunication.config.ProblemType;
import jakarta.inject.Singleton;

import java.util.UUID;

@Singleton
class RoomAccessValidatorImpl implements RoomAccessValidator {

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

}
