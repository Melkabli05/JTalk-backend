package com.jtalk.livecommunication.integration;

import com.jtalk.livecommunication.domain.model.ParticipantPreview;

import com.jtalk.livecommunication.domain.model.ChatEvent;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import io.micronaut.core.annotation.NonNull;

import java.util.List;
import java.util.UUID;

/**
 * Outbound media server boundary (LiveKit): rooms, signed join tokens, permissions, subscriptions and moderation.
 * A LiveKit room is addressed by the voice room id.
 */
public interface LiveKitGateway {

    void createRoom(@NonNull UUID roomId);

    /** Disconnects everyone and discards the media room. Idempotent. */
    void deleteRoom(@NonNull UUID roomId);

    /** The websocket URL clients connect to with a token from {@link #generateToken}. */
    String serverUrl();

    String generateToken(@NonNull UUID roomId, @NonNull UUID userId, boolean canPublish);

    default String generateToken(@NonNull UUID roomId, @NonNull UUID userId, boolean canPublish, ParticipantPreview profile) {
        return generateToken(roomId, userId, canPublish);
    }

    /** Grants or revokes publish rights of a participant that is already connected. Idempotent. */
    void setPublishPermission(@NonNull UUID roomId, @NonNull UUID userId, boolean canPublish);

    /**
     * Mutes the participant's published microphone on the media server, so it stops reaching everyone at once. They can unmute
     * themselves again. Does nothing if they are not connected or publish no microphone.
     */
    void muteMicrophone(@NonNull UUID roomId, @NonNull UUID userId);

    /** (Un)subscribes one participant from the given tracks. An empty list does nothing. */
    void updateSubscriptions(@NonNull UUID roomId, @NonNull UUID userId, @NonNull List<String> trackSids, boolean subscribe);

    /** Disconnects a participant and revokes every token issued to them so far, so they cannot reconnect with one. */
    void removeParticipant(@NonNull UUID roomId, @NonNull UUID userId);

    /**
     * Pushes a room event to everyone in the room over LiveKit's reliable data channel, so clients update their UI without
     * polling. Best effort: a failure is logged and never fails the request that caused the event.
     */
    void broadcastRoomEvent(@NonNull RoomRealtimeEvent event);

    /** The same for a change to the room's chat, on a topic of its own so a burst of comments never queues behind a stage change. */
    void broadcastChatEvent(@NonNull ChatEvent event);
}
