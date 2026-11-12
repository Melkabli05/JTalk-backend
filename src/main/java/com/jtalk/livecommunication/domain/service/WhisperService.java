package com.jtalk.livecommunication.domain.service;

import java.util.UUID;

/**
 * Private side-channel from a stage speaker to one participant. The speaker publishes a track named
 * {@value #TRACK_PREFIX}{@code <targetId>}; the server then unsubscribes everyone except the speaker and
 * the target from it (LiveKit's documented {@code UpdateSubscriptions}), and signals both clients over a
 * data message so the target's client can duck the stage audio.
 */
public interface WhisperService {

    String TRACK_PREFIX = "whisper_target_";

    void start(UUID roomId, String speakerId, String targetId);

    /** Idempotent. */
    void stop(UUID roomId, String speakerId);

    /** A track named {@code whisper_target_*} was published: keep everyone but the speaker and target off it. */
    void onWhisperTrackPublished(UUID roomId, String speakerId, String trackName, String trackSid);
}
