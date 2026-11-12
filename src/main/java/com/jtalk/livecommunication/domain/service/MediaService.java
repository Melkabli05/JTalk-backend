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

    /** An audio track currently published in a room. */
    record MediaTrack(String identity, String sid, String name) {
    }

    MediaConnection issueConnection(UUID roomId, String userId, boolean canPublish);

    /** Grants or revokes publish rights of a participant that is already connected. Idempotent. */
    void updatePublishPermission(UUID roomId, String userId, boolean canPublish);

    /** Disconnects everyone and discards the media room. Idempotent. */
    void closeRoom(UUID roomId);

    /** All tracks currently published in the room; empty if the media room does not exist yet. */
    List<MediaTrack> listTracks(UUID roomId);

    /** Server-side (un)subscribes one participant from the given tracks. */
    void setSubscriptions(UUID roomId, String userId, List<String> trackSids, boolean subscribe);

    /** Sends a reliable data message to specific participants of the room. */
    void sendSignal(UUID roomId, String topic, byte[] payload, List<String> destinationIdentities);
}
