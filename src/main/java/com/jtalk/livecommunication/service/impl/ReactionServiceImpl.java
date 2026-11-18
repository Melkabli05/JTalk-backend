package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.config.ProblemType;
import com.jtalk.livecommunication.domain.enums.Reaction;
import com.jtalk.livecommunication.repository.PresenceRepository;
import com.jtalk.livecommunication.service.ReactionService;
import com.jtalk.livecommunication.service.VoiceRoomService;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class ReactionServiceImpl implements ReactionService {

    private final VoiceRoomService rooms;
    private final PresenceRepository presence;
    private final ReactionThrottle throttle;
    private final RoomEvents events;

    @Override
    public void react(UUID roomId, UUID userId, Reaction reaction) {
        rooms.getRequiredActiveRoom(roomId);
        if (!throttle.allow(roomId, userId)) {
            throw ProblemType.REACTING_TOO_FAST.because("Wait a moment before reacting again");
        }
        if (!presence.isPresent(roomId, userId)) {
            throw ProblemType.NOT_IN_ROOM.because(userId + " has not joined room " + roomId);
        }
        events.reacted(roomId, userId, reaction);
    }
}
