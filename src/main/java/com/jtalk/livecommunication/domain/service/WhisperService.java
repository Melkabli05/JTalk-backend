package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.service.MediaService.MediaConnection;

import java.util.UUID;

/**
 * Private side-channel from a stage speaker to one participant. Each whisper gets its own two-person LiveKit room, so
 * the audio never touches the main room and nobody else can hear it. The speaker receives their connection in the
 * response; the target receives theirs in a data message addressed only to them. Stopping deletes the room.
 */
public interface WhisperService {

    /** Starts a whisper (ending the speaker's previous one, if any) and returns the speaker's connection. */
    MediaConnection start(UUID roomId, String speakerId, String targetId);

    /** Idempotent. */
    void stop(UUID roomId, String speakerId);
}
