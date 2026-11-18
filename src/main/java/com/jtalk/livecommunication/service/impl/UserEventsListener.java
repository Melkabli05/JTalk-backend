package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.model.UserEvent;
import com.jtalk.livecommunication.service.VoiceRoomService;
import io.micronaut.configuration.lettuce.pubsub.annotation.MessageChannel;
import io.micronaut.configuration.lettuce.pubsub.annotation.RedisListener;
import io.micronaut.messaging.annotation.MessageBody;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;

/**
 * Listens to what the user service announces, on the Redis both services share. When an account is deleted, the rooms its owner was
 * running are closed: with no account there is no one to host, edit or end them. The work is off the listener's thread, which must not
 * wait for a database; every instance hears the announcement and each closes what is still open, so doing it twice is harmless.
 */
@RedisListener
@Slf4j
class UserEventsListener {

    static final String CHANNEL = "user-events";

    private final VoiceRoomService rooms;
    private final ExecutorService background;

    UserEventsListener(VoiceRoomService rooms, @Named(TaskExecutors.VIRTUAL) ExecutorService background) {
        this.rooms = rooms;
        this.background = background;
    }

    @MessageChannel(CHANNEL)
    void receive(@MessageBody UserEvent event) {
        if (event.isDeletion()) {
            background.execute(() -> closeHostedRooms(event));
        }
    }

    private void closeHostedRooms(UserEvent event) {
        try {
            rooms.closeRoomsHostedBy(event.userId());
        } catch (RuntimeException e) {
            log.warn("Could not close the rooms of deleted user {}", event.userId(), e);
        }
    }
}
