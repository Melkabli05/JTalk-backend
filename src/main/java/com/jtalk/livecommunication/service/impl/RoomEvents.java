package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.enums.Reaction;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.model.PresenceChange;
import com.jtalk.livecommunication.domain.model.ReactionBurst;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.domain.model.RoomSettings;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import com.jtalk.livecommunication.repository.LiveStatsRepository;
import com.jtalk.livecommunication.repository.StageRepository;
import com.jtalk.livecommunication.service.RoomFeed;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Slf4j
@RequiredArgsConstructor
class RoomEvents {

    private static final int MAX_REACTIONS_PER_MESSAGE = 24;

    private static final class Pending {
        final Set<UUID> joined = new LinkedHashSet<>();
        final Set<UUID> left = new LinkedHashSet<>();
    }

    private final LiveKitGateway gateway;
    private final StageRepository stage;
    private final LiveStatsRepository liveStats;
    private final Clock clock;
    private final RoomFeed feed;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, List<ReactionBurst>> reactions = new ConcurrentHashMap<>();

    void announce(UUID roomId, RoomEventType type, @Nullable UUID targetUserId) {
        gateway.broadcastRoomEvent(new RoomRealtimeEvent(type, roomId, targetUserId, clock.instant(), null, null, null, null));
    }

    void announceStage(UUID roomId, RoomEventType type, @Nullable UUID targetUserId) {
        var at = clock.instant();
        gateway.broadcastRoomEvent(new RoomRealtimeEvent(type, roomId, targetUserId, at, stage.snapshot(roomId), null, null, null));
        pending.computeIfAbsent(roomId, id -> new Pending());   // the hallway's card shows who is on stage: tell it with the next batch
    }

    void announceSettings(UUID roomId, RoomSettings settings) {
        gateway.broadcastRoomEvent(new RoomRealtimeEvent(RoomEventType.ROOM_UPDATED, roomId, null, clock.instant(), null, null, null, settings));
    }

    void userJoined(UUID roomId, UUID userId) {
        pending.compute(roomId, (id, changes) -> {
            Pending next = changes == null ? new Pending() : changes;
            next.left.remove(userId);
            next.joined.add(userId);
            return next;
        });
    }

    void userLeft(UUID roomId, UUID userId) {
        pending.compute(roomId, (id, changes) -> {
            Pending next = changes == null ? new Pending() : changes;
            next.joined.remove(userId);
            next.left.add(userId);
            return next;
        });
    }

    @Scheduled(fixedDelay = "${voice-room.presence-batch:2s}")
    void sendPresenceChanges() {
        Map<UUID, Pending> changed = new LinkedHashMap<>();
        for (UUID roomId : List.copyOf(pending.keySet())) {
            Pending changes = pending.remove(roomId);
            if (changes != null) {
                changed.put(roomId, changes);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        // One pipelined read for every room that changed, not one round trip per room.
        Map<UUID, RoomLiveStats> stats = liveStats.find(changed.keySet());
        changed.forEach((roomId, changes) -> {
            feed.stats(roomId, stats.get(roomId));
            if (changes.joined.isEmpty() && changes.left.isEmpty()) {
                return;   // only the stage moved: the room already heard that
            }
            try {
                gateway.broadcastRoomEvent(new RoomRealtimeEvent(RoomEventType.PRESENCE_CHANGED, roomId, null, clock.instant(), null,
                        new PresenceChange(stats.get(roomId).participantCount(), new ArrayList<>(changes.joined), new ArrayList<>(changes.left)), null, null));
            } catch (RuntimeException e) {
                log.warn("Could not announce who came and went in room {}", roomId, e);
            }
        });
    }

    void reacted(UUID roomId, UUID userId, Reaction reaction) {
        reactions.compute(roomId, (id, bursts) -> {
            List<ReactionBurst> next = bursts == null ? new ArrayList<>() : bursts;
            if (next.size() < MAX_REACTIONS_PER_MESSAGE) {
                next.add(new ReactionBurst(userId, reaction));
            }
            return next;
        });
    }

    @Scheduled(fixedDelay = "${voice-room.reaction-batch:300ms}")
    void sendReactions() {
        for (UUID roomId : List.copyOf(reactions.keySet())) {
            List<ReactionBurst> bursts = reactions.remove(roomId);
            if (bursts == null || bursts.isEmpty()) {
                continue;
            }
            try {
                gateway.broadcastRoomEvent(new RoomRealtimeEvent(RoomEventType.REACTIONS, roomId, null, clock.instant(), null, null, bursts, null));
            } catch (RuntimeException e) {
                log.warn("Could not announce reactions in room {}", roomId, e);
            }
        }
    }
}
