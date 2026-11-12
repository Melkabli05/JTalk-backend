package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.SeatResult;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.model.StageState;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.exception.ProblemType;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.zalando.problem.ThrowableProblem;

import java.util.Optional;
import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class StageServiceImpl implements StageService {

    private final VoiceRoomService rooms;
    private final StageRepository stage;
    private final MediaService media;

    @Override
    public int raiseHand(UUID roomId, String userId) {
        VoiceRoom room = rooms.getActive(roomId);
        if (room.isHost(userId)) {
            throw ProblemType.HOST_DOES_NOT_NEED_SEAT.because("The host can always speak and does not need a stage seat");
        }
        int position = stage.raiseHand(roomId, userId);
        if (position < 0) {
            throw alreadyOnStage(userId);
        }
        return position;
    }

    @Override
    public void lowerHand(UUID roomId, String userId) {
        rooms.getActive(roomId);
        stage.lowerHand(roomId, userId);
    }

    @Override
    public void approveSpeaker(UUID roomId, String hostId, String userId) {
        VoiceRoom room = rooms.getActiveAsHost(roomId, hostId);
        SeatResult result = stage.approve(roomId, userId, room.maxSpeakers());
        switch (result) {
            case STAGE_FULL -> throw ProblemType.STAGE_FULL.because("All " + room.maxSpeakers() + " stage seats are taken");
            case ALREADY_ON_STAGE -> throw alreadyOnStage(userId);
            case NOT_IN_QUEUE -> throw ProblemType.NOT_IN_HAND_RAISE_QUEUE.because(userId + " has not raised a hand");
            case SEATED -> {
                try {
                    media.updatePublishPermission(roomId, userId, true);
                } catch (RuntimeException e) {
                    stage.leaveStage(roomId, userId);
                    throw e;
                }
            }
        }
    }

    @Override
    public void removeSpeaker(UUID roomId, String requesterId, String userId) {
        VoiceRoom room = rooms.getActive(roomId);
        if (!requesterId.equals(userId) && !room.isHost(requesterId)) {
            throw ProblemType.CANNOT_REMOVE_SPEAKER.because("Only the host can remove another speaker from the stage");
        }
        stage.leaveStage(roomId, userId);
        if (!room.isHost(userId)) {
            media.updatePublishPermission(roomId, userId, false);
        }
    }

    @Override
    public StageState getStage(UUID roomId) {
        rooms.get(roomId);
        return stage.snapshot(roomId);
    }

    @Override
    public boolean isOnStage(UUID roomId, String userId) {
        return stage.isOnStage(roomId, userId);
    }

    @Override
    public void clearUser(UUID roomId, String userId) {
        stage.lowerHand(roomId, userId);
        stage.leaveStage(roomId, userId);
    }

    @Override
    public Optional<String> releaseSeatAndPromoteNext(UUID roomId, String userId) {
        VoiceRoom room = rooms.get(roomId);
        if (!room.isActive()) {
            return Optional.empty();   // the room-closed event clears the stage
        }
        Optional<String> promoted = stage.releaseAndPromoteNext(roomId, userId, room.maxSpeakers());
        promoted.ifPresent(promotedId -> {
            try {
                media.updatePublishPermission(roomId, promotedId, true);
            } catch (RuntimeException e) {
                stage.leaveStage(roomId, promotedId);
                throw e;
            }
        });
        return promoted;
    }

    @EventListener
    void onRoomClosed(VoiceRoomClosed event) {
        stage.clear(event.roomId());
    }

    private static ThrowableProblem alreadyOnStage(String userId) {
        return ProblemType.ALREADY_ON_STAGE.because(userId + " is already on stage");
    }
}
