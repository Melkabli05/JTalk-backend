package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.repository.RoomFeedChannel;
import com.jtalk.livecommunication.service.RoomFeed;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Everything goes through Redis, this instance's own events included: one path, one order, whichever instance an event came from. The
 * best effort of a feed is deliberate: if Redis cannot be reached the room is still opened or closed, only the hallways are not told.
 */
@Singleton
@Slf4j
class RoomFeedImpl implements RoomFeed {

    /** How far behind a watcher may fall before it is cut off (it reconnects and loads the list again). */
    private static final int MAX_BACKLOG = 256;

    private final RoomFeedChannel channel;
    private final JsonMapper json;
    private final Sinks.Many<RoomFeedEvent> events = Sinks.many().multicast().directBestEffort();

    RoomFeedImpl(RoomFeedChannel channel, JsonMapper json) {
        this.channel = channel;
        this.json = json;
        channel.receiver(this::received);
        channel.onReconnected(() -> deliver(RoomFeedEvent.READY));
    }

    @Override
    public Publisher<RoomFeedEvent> subscribe() {
        return Flux.defer(() -> {
            var listening = channel.listen();
            // merge subscribes to the sink first and emits READY only once Redis has confirmed the subscription, so nothing published
            // after READY can be missed.
            return Flux.merge(events.asFlux().onBackpressureBuffer(MAX_BACKLOG, BufferOverflowStrategy.ERROR),
                            Mono.fromFuture(listening).thenReturn(RoomFeedEvent.READY))
                    .doFinally(signal -> channel.stopListening());
        });
    }

    @Override
    public boolean anyoneWatching() {
        return channel.anyoneWatching();
    }

    @Override
    public void opened(VoiceRoomResponse room) {
        publish(RoomFeedEvent.opened(room));
    }

    @Override
    public void updated(VoiceRoomResponse room) {
        publish(RoomFeedEvent.updated(room));
    }

    @Override
    public void closed(UUID roomId) {
        publish(RoomFeedEvent.closed(roomId));
    }

    @Override
    public void stats(UUID roomId, RoomLiveStats live) {
        publish(RoomFeedEvent.stats(roomId, live));
    }

    private void publish(RoomFeedEvent event) {
        try {
            channel.publish(json.writeValueAsString(event));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not tell the hallways about {}", event.type(), e);
        }
    }

    private void received(String message) {
        try {
            deliver(json.readValue(message.getBytes(StandardCharsets.UTF_8), RoomFeedEvent.class));
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring a room feed message that could not be read", e);
        }
    }

    private void deliver(RoomFeedEvent event) {
        if (events.currentSubscriberCount() > 0) {
            // Emitters on other threads wait their turn instead of failing.
            events.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
        }
    }
}
