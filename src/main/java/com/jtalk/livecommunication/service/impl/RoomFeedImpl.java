package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.service.RoomFeed;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.UUID;

@Singleton
class RoomFeedImpl implements RoomFeed {

    /** How far behind a watcher may fall before it is cut off (it reconnects and loads the list again). */
    private static final int MAX_BACKLOG = 256;

    private final Sinks.Many<RoomFeedEvent> events = Sinks.many().multicast().directBestEffort();

    @Override
    public Publisher<RoomFeedEvent> subscribe() {
        // merge subscribes to the sink first and only then emits READY, so nothing published after READY can be missed.
        return Flux.merge(events.asFlux().onBackpressureBuffer(MAX_BACKLOG, BufferOverflowStrategy.ERROR), Flux.just(RoomFeedEvent.READY));
    }

    @Override
    public boolean hasWatchers() {
        return events.currentSubscriberCount() > 0;
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
        if (hasWatchers()) {
            // Emitters on other threads wait their turn instead of failing.
            events.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
        }
    }
}
