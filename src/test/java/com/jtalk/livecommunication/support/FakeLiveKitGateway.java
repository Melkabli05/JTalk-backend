package com.jtalk.livecommunication.support;

import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;

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

    public record Removal(UUID roomId, UUID userId) {
    }

    /** When set, {@link #createRoom} fails with it, to test what happens if the media server is down. */
    public volatile RuntimeException createRoomFailure;
    /** While set, deleting a room waits for it to be counted down: a media server that is slow to answer. */
    public volatile java.util.concurrent.CountDownLatch deleteRoomGate;
    public final List<RoomRealtimeEvent> events = new CopyOnWriteArrayList<>();
    public final List<RoomRealtimeEvent> presenceEvents = new CopyOnWriteArrayList<>();
    public final List<com.jtalk.livecommunication.domain.model.ChatEvent> chatEvents = new CopyOnWriteArrayList<>();
    public final List<UUID> createdRooms = new CopyOnWriteArrayList<>();
    public final List<UUID> closedRooms = new CopyOnWriteArrayList<>();
    public final List<PermissionChange> permissionChanges = new CopyOnWriteArrayList<>();
    public final List<SubscriptionChange> subscriptionChanges = new CopyOnWriteArrayList<>();
    public final List<Removal> mutes = new CopyOnWriteArrayList<>();
    public final List<Removal> removals = new CopyOnWriteArrayList<>();

    public void reset() {
        createRoomFailure = null;
        events.clear();
        deleteRoomGate = null;
        presenceEvents.clear();
        chatEvents.clear();
        createdRooms.clear();
        closedRooms.clear();
        permissionChanges.clear();
        subscriptionChanges.clear();
        removals.clear();
        mutes.clear();
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
        java.util.concurrent.CountDownLatch gate = deleteRoomGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closedRooms.add(roomId);
    }

    public static final String SERVER_URL = "wss://livekit.test";

    @Override
    public String serverUrl() {
        return SERVER_URL;
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
    public void muteMicrophone(UUID roomId, UUID userId) {
        mutes.add(new Removal(roomId, userId));
    }

    @Override
    public void removeParticipant(UUID roomId, UUID userId) {
        removals.add(new Removal(roomId, userId));
    }

    @Override
    public void broadcastChatEvent(com.jtalk.livecommunication.domain.model.ChatEvent event) {
        chatEvents.add(event);
    }

    @Override
    public void broadcastRoomEvent(RoomRealtimeEvent event) {
        (event.type() == RoomEventType.PRESENCE_CHANGED ? presenceEvents : events).add(event);
    }
}
