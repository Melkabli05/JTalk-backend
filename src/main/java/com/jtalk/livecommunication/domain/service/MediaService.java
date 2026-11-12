package com.jtalk.livecommunication.domain.service;

import java.util.List;
import java.util.UUID;

/**
 * Real-time media server (LiveKit). A LiveKit room is addressed by the voice room id and is created
 * lazily by LiveKit when the first participant joins.
 */
public interface MediaService {

    /** Credentials a client needs to connect to the media server. */
    record MediaConnection(String url, String token) {
    }

    MediaConnection issueConnection(UUID roomId, String userId, boolean canPublish);

    /**
     * Connection to the private two-person LiveKit room of one whisper (nobody else can join it). The speaker may only
     * publish and the target may only listen.
     */
    MediaConnection issueWhisperConnection(UUID roomId, String speakerId, String targetId, String userId);

    /** Deletes the whisper's private room, disconnecting both. Idempotent. */
    void closeWhisper(UUID roomId, String speakerId, String targetId);

    /** Grants or revokes publish rights of a participant that is already connected. Idempotent. */
    void updatePublishPermission(UUID roomId, String userId, boolean canPublish);

    /** Disconnects a participant and revokes every token issued to them so far, so they cannot reconnect with one. */
    void removeParticipant(UUID roomId, String userId);

    /** Disconnects everyone and discards the media room. Idempotent. */
    void closeRoom(UUID roomId);

    /** Server-side (un)subscribes one participant from the given tracks. */
    void setSubscriptions(UUID roomId, String userId, List<String> trackSids, boolean subscribe);

    /** Sends a reliable data message to specific participants of the room. */
    void sendSignal(UUID roomId, String topic, byte[] payload, List<String> destinationIdentities);
}
