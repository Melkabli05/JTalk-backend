package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.model.UserEvent;
import com.jtalk.livecommunication.repository.PresenceRepository;
import com.jtalk.livecommunication.service.ParticipantService;
import com.jtalk.livecommunication.service.VoiceRoomService;
import io.micronaut.configuration.lettuce.pubsub.annotation.MessageChannel;
import io.micronaut.configuration.lettuce.pubsub.annotation.RedisListener;
import io.micronaut.messaging.annotation.MessageBody;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Listens to what the user service announces, on the Redis both services share. When an account is deleted, the rooms its owner was
 * running go to someone on their stage, or are closed when there is no one ({@link HostSuccession}), they are taken out of the rooms they
 * were in, and what they ever hosted is erased. When a profile is updated (e.g., picture), the change is broadcast to all rooms
 * the user is in. The work is off the listener's thread, which must not wait for a database; every instance hears the announcement 
 * and each closes what is still open, so doing it twice is harmless.
 */
@RedisListener
@Slf4j
class UserEventsListener {

    static final String CHANNEL = "user-events";

    private final VoiceRoomService rooms;
    private final HostSuccession succession;
    private final ParticipantService participants;
    private final PresenceRepository presence;
    private final RoomEvents events;
    private final ExecutorService background;

    UserEventsListener(VoiceRoomService rooms, HostSuccession succession, ParticipantService participants,
                       PresenceRepository presence, RoomEvents events,
                       @Named(TaskExecutors.VIRTUAL) ExecutorService background) {
        this.rooms = rooms;
        this.succession = succession;
        this.participants = participants;
        this.presence = presence;
        this.events = events;
        this.background = background;
    }

    @MessageChannel(CHANNEL)
    void receive(@MessageBody UserEvent event) {
        if (event.isDeletion()) {
            background.execute(() -> clean(event));
        } else if (event.isProfileUpdated()) {
            background.execute(() -> broadcastProfileUpdate(event));
        }
    }

    /**
     * When a user updates their profile, broadcast the change to all rooms they're in.
     */
    private void broadcastProfileUpdate(UserEvent event) {
        UUID userId = event.userId();
        String avatarUrl = event.avatar();
        if (avatarUrl == null) {
            return;
        }
        try {
            for (UUID roomId : rooms.openRoomIds()) {
                if (presence.isPresent(roomId, userId)) {
                    events.announceParticipantProfileUpdate(roomId, userId, avatarUrl);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not broadcast profile update for user {}", userId, e);
        }
    }

    /**
     * What a deleted account leaves behind, each step tried even if an earlier one failed: the rooms they run are handed over or closed,
     * they are taken out of the rooms they are in, and what they ever hosted is erased. The order matters: a room is handed over before the
     * person is removed (so they are no longer its host), and erased only once it is closed or no longer theirs.
     */
    private void clean(UserEvent event) {
        step("hand over or close the rooms hosted by", event, () -> succession.retire(event.userId()));
        step("remove from the rooms of other people", event, () -> participants.removeFromAllRooms(event.userId()));
        step("erase the rooms hosted by", event, () -> rooms.eraseRoomsHostedBy(event.userId()));
    }

    private void step(String what, UserEvent event, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("Could not {} deleted user {}", what, event.userId(), e);
        }
    }
}

