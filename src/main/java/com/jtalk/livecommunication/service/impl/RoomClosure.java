package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import com.jtalk.livecommunication.repository.PresenceRepository;
import com.jtalk.livecommunication.repository.PrunedTrackRepository;
import com.jtalk.livecommunication.repository.StageRepository;
import com.jtalk.livecommunication.service.RoomFeed;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Everything that follows a room being closed, after the database write has committed and off the request, so the host's answer waits for
 * none of it. The room is already closed in the source of truth; this only tidies up, and none of it is needed for that to hold: Redis keys
 * expire by themselves and LiveKit empties idle rooms. Each step is tried even if an earlier one failed.
 */
@Singleton
@Slf4j
class RoomClosure {

    private final ExecutorService background;
    private final RoomFeed feed;
    private final StageRepository stage;
    private final PrunedTrackRepository prunedTracks;
    private final PresenceRepository presence;
    private final LiveKitGateway gateway;

    RoomClosure(@Named(TaskExecutors.VIRTUAL) ExecutorService background, RoomFeed feed, StageRepository stage,
                PrunedTrackRepository prunedTracks, PresenceRepository presence, LiveKitGateway gateway) {
        this.background = background;
        this.feed = feed;
        this.stage = stage;
        this.prunedTracks = prunedTracks;
        this.presence = presence;
        this.gateway = gateway;
    }

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        background.execute(() -> tidyUp(event.roomId()));
    }

    private void tidyUp(UUID roomId) {
        feed.closed(roomId);   // first: the hallways should hear it soonest
        attempt(roomId, "clear the stage", () -> stage.clear(roomId));
        attempt(roomId, "clear the pruned tracks", () -> prunedTracks.clear(roomId));
        attempt(roomId, "clear who is present", () -> presence.clear(roomId));
        attempt(roomId, "delete the media room", () -> gateway.deleteRoom(roomId));
    }

    private static void attempt(UUID roomId, String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("Room {} is closed but could not {}", roomId, what, e);
        }
    }
}
