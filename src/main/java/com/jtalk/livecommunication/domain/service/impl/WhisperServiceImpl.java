package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.model.WhisperSignal;
import com.jtalk.livecommunication.domain.repository.WhisperRepository;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.domain.service.MediaService.MediaConnection;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.domain.service.WhisperService;
import com.jtalk.livecommunication.exception.ProblemType;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

@Singleton
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
    public MediaConnection start(UUID roomId, String speakerId, String targetId) {
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
        stop(roomId, speakerId);   // one whisper at a time
        whispers.start(roomId, speakerId, targetId);
        MediaConnection forTarget = media.issueWhisperConnection(roomId, speakerId, targetId, targetId);
        send(roomId, targetId, new WhisperSignal("start", speakerId, forTarget.url(), forTarget.token()));
        return media.issueWhisperConnection(roomId, speakerId, targetId, speakerId);
    }

    @Override
    public void stop(UUID roomId, String speakerId) {
        whispers.find(roomId, speakerId).ifPresent(targetId -> {
            whispers.stop(roomId, speakerId);
            media.closeWhisper(roomId, speakerId, targetId);
            send(roomId, targetId, new WhisperSignal("stop", speakerId, null, null));
        });
    }

    private void send(UUID roomId, String targetId, WhisperSignal signal) {
        try {
            media.sendSignal(roomId, TOPIC, json.writeValueAsBytes(signal), List.of(targetId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
