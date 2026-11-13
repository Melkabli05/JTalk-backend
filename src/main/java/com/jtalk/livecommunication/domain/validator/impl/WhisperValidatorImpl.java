package com.jtalk.livecommunication.domain.validator.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import com.jtalk.livecommunication.domain.validator.WhisperValidator;
import com.jtalk.livecommunication.exception.ProblemType;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class WhisperValidatorImpl implements WhisperValidator {

    private final StageRepository stage;
    private final PresenceRepository presence;

    @Override
    public void requireAllowed(VoiceRoom room, UUID speakerId, UUID targetId) {
        if (!room.allowWhisper()) {
            throw ProblemType.WHISPER_NOT_PERMITTED.because("Whisper side-channels are disabled in this room");
        }
        if (!stage.isOnStage(room.id(), speakerId)) {
            throw ProblemType.WHISPER_NOT_PERMITTED.because("Only stage speakers can whisper");
        }
        if (speakerId.equals(targetId)) {
            throw ProblemType.WHISPER_TO_SELF.because("You cannot whisper to yourself");
        }
        if (!presence.isPresent(room.id(), targetId)) {
            throw ProblemType.WHISPER_TARGET_NOT_PRESENT.because(targetId + " is not in the room");
        }
    }
}
