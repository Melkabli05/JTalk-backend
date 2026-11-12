package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.model.Participant;
import com.jtalk.livecommunication.domain.model.WhisperSignal;
import com.jtalk.livecommunication.domain.repository.WhisperRepository;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.domain.service.WhisperService;
import com.jtalk.livecommunication.exception.ProblemType;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor
class WhisperServiceImpl implements WhisperService {

    private static final String TOPIC = "whisper";

    private final VoiceRoomService rooms;
    private final StageService stage;
    private final ParticipantService participants;
    private final WhisperRepository whispers;
    private final MediaService media;
    private final ObjectMapper json;

    @Override
    public void start(UUID roomId, String speakerId, String targetId) {
        VoiceRoom room = rooms.getActive(roomId);
        if (!room.isHost(speakerId) && !stage.isOnStage(roomId, speakerId)) {
            throw ProblemType.WHISPER_NOT_PERMITTED.because("Only the host and stage speakers can whisper");
        }
        if (speakerId.equals(targetId)) {
            throw ProblemType.WHISPER_TO_SELF.because("You cannot whisper to yourself");
        }
        if (!participants.isPresent(roomId, targetId)) {
            throw ProblemType.WHISPER_TARGET_NOT_PRESENT.because(targetId + " is not in the room");
        }
        whispers.start(roomId, speakerId, targetId);
        send("start", roomId, speakerId, targetId);
    }

    @Override
    public void stop(UUID roomId, String speakerId) {
        rooms.getActive(roomId);
        whispers.find(roomId, speakerId).ifPresent(targetId -> {
            whispers.stop(roomId, speakerId);
            send("stop", roomId, speakerId, targetId);
        });
    }

    @Override
    public void onWhisperTrackPublished(UUID roomId, String speakerId, String trackName, String trackSid) {
        String targetId = trackName.substring(TRACK_PREFIX.length());
        if (!whispers.find(roomId, speakerId).filter(targetId::equals).isPresent()) {
            log.warn("Ignoring whisper track {} from {}: no matching whisper session", trackName, speakerId);
            return;
        }
        participants.listPresent(roomId).stream()
                .map(Participant::userId)
                .filter(userId -> !userId.equals(speakerId) && !userId.equals(targetId))
                .forEach(userId -> media.setSubscriptions(roomId, userId, List.of(trackSid), false));
    }

    private void send(String action, UUID roomId, String speakerId, String targetId) {
        try {
            byte[] payload = json.writeValueAsBytes(
                    new WhisperSignal(action, speakerId, targetId, TRACK_PREFIX + targetId));
            media.sendSignal(roomId, TOPIC, payload, List.of(speakerId, targetId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
