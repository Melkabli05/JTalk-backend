package com.jtalk.livecommunication.domain.validator;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;

import java.util.UUID;

/** The preconditions of a private whisper, checked in the order a client would want to hear about them. */
public interface WhisperValidator {

    /**
     * @throws io.micronaut.http.exceptions.HttpStatusException 403 {@code whisper-not-permitted} if the room has
     *         whispering switched off or the speaker is not on stage; 422 {@code whisper-to-self}; 404
     *         {@code whisper-target-not-present} if the other person is not currently in the room
     */
    void requireAllowed(VoiceRoom room, UUID speakerId, UUID targetId);
}
