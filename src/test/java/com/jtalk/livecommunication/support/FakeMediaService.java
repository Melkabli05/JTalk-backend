package com.jtalk.livecommunication.support;

import com.jtalk.livecommunication.domain.service.MediaService;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for LiveKit in tests: records what the application asked the media server to do. */
@Singleton
@Replaces(MediaService.class)
public class FakeMediaService implements MediaService {

    public record PermissionChange(UUID roomId, String userId, boolean canPublish) {
    }

    public record SubscriptionChange(UUID roomId, String userId, List<String> trackSids, boolean subscribe) {
    }

    public record Signal(UUID roomId, String topic, String payload, List<String> destinationIdentities) {
    }

    public final List<PermissionChange> permissionChanges = new CopyOnWriteArrayList<>();
    public final List<SubscriptionChange> subscriptionChanges = new CopyOnWriteArrayList<>();
    public final List<Signal> signals = new CopyOnWriteArrayList<>();
    public record Removal(UUID roomId, String userId) {
    }

    public final List<Removal> removals = new CopyOnWriteArrayList<>();
    public final List<UUID> closedRooms = new CopyOnWriteArrayList<>();

    public void reset() {
        permissionChanges.clear();
        subscriptionChanges.clear();
        signals.clear();
        closedRooms.clear();
        removals.clear();
    }

    @Override
    public MediaConnection issueConnection(UUID roomId, String userId, boolean canPublish) {
        return new MediaConnection("ws://fake-livekit", "token:" + roomId + ":" + userId + ":publish=" + canPublish);
    }

    @Override
    public void updatePublishPermission(UUID roomId, String userId, boolean canPublish) {
        permissionChanges.add(new PermissionChange(roomId, userId, canPublish));
    }

    @Override
    public void removeParticipant(UUID roomId, String userId) {
        removals.add(new Removal(roomId, userId));
    }

    @Override
    public void closeRoom(UUID roomId) {
        closedRooms.add(roomId);
    }

    @Override
    public void setSubscriptions(UUID roomId, String userId, List<String> trackSids, boolean subscribe) {
        subscriptionChanges.add(new SubscriptionChange(roomId, userId, List.copyOf(trackSids), subscribe));
    }

    @Override
    public void sendSignal(UUID roomId, String topic, byte[] payload, List<String> destinationIdentities) {
        signals.add(new Signal(roomId, topic, new String(payload, StandardCharsets.UTF_8), List.copyOf(destinationIdentities)));
    }
}
