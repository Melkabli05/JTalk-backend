package com.jtalk.livecommunication.support;

import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for LiveKit in tests: records what the application asked the media server to do. */
@Singleton
@Replaces(LiveKitGateway.class)
public class FakeLiveKitGateway implements LiveKitGateway {

    public record PermissionChange(UUID roomId, UUID userId, boolean canPublish) {
    }

    public record SubscriptionChange(UUID roomId, UUID userId, List<String> trackSids, boolean subscribe) {
    }

    public record Signal(UUID roomId, String topic, String payload, List<UUID> destinations) {
    }

    public record Removal(UUID roomId, UUID userId) {
    }

    public record WhisperClose(UUID roomId, UUID speakerId, UUID targetId) {
    }

    /** When set, {@link #createRoom} fails with it, to test what happens if the media server is down. */
    public volatile RuntimeException createRoomFailure;
    public final List<RoomRealtimeEvent> events = new CopyOnWriteArrayList<>();
    public final List<UUID> createdRooms = new CopyOnWriteArrayList<>();
    public final List<UUID> closedRooms = new CopyOnWriteArrayList<>();
    public final List<PermissionChange> permissionChanges = new CopyOnWriteArrayList<>();
    public final List<SubscriptionChange> subscriptionChanges = new CopyOnWriteArrayList<>();
    public final List<Signal> signals = new CopyOnWriteArrayList<>();
    public final List<Removal> removals = new CopyOnWriteArrayList<>();
    public final List<WhisperClose> closedWhispers = new CopyOnWriteArrayList<>();

    public void reset() {
        createRoomFailure = null;
        events.clear();
        createdRooms.clear();
        closedRooms.clear();
        permissionChanges.clear();
        subscriptionChanges.clear();
        signals.clear();
        removals.clear();
        closedWhispers.clear();
    }

    @Override
    public void createRoom(UUID roomId) {
        if (createRoomFailure != null) {
            throw createRoomFailure;
        }
        createdRooms.add(roomId);
    }

    @Override
    public void deleteRoom(UUID roomId) {
        closedRooms.add(roomId);
    }

    @Override
    public String generateToken(UUID roomId, UUID userId, boolean canPublish) {
        return "token:" + userId + ":publish=" + canPublish;
    }

    @Override
    public void setPublishPermission(UUID roomId, UUID userId, boolean canPublish) {
        permissionChanges.add(new PermissionChange(roomId, userId, canPublish));
    }

    /** Like the real gateway, an empty list is a no-op. */
    @Override
    public void updateSubscriptions(UUID roomId, UUID userId, List<String> trackSids, boolean subscribe) {
        if (!trackSids.isEmpty()) {
            subscriptionChanges.add(new SubscriptionChange(roomId, userId, List.copyOf(trackSids), subscribe));
        }
    }

    @Override
    public void removeParticipant(UUID roomId, UUID userId) {
        removals.add(new Removal(roomId, userId));
    }

    @Override
    public String generateWhisperToken(UUID roomId, UUID speakerId, UUID targetId, UUID userId) {
        return "whisper:" + speakerId + ">" + targetId + ":" + userId + ":publish=" + userId.equals(speakerId);
    }

    @Override
    public void deleteWhisperRoom(UUID roomId, UUID speakerId, UUID targetId) {
        closedWhispers.add(new WhisperClose(roomId, speakerId, targetId));
    }

    @Override
    public void broadcastRoomEvent(UUID roomId, RoomEventType type, UUID targetUserId) {
        RoomRealtimeEvent event = new RoomRealtimeEvent(type, roomId, targetUserId, java.time.Instant.now());
        events.add(event);
    }

    @Override
    public void sendData(UUID roomId, String topic, Object message, List<UUID> destinationUserIds) {
        try {
            signals.add(new Signal(roomId, topic, ObjectMapper.getDefault().writeValueAsString(message), List.copyOf(destinationUserIds)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
