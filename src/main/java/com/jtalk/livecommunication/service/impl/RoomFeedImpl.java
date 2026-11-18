package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.service.RoomFeed;
import io.micronaut.messaging.annotation.MessageBody;
import io.micronaut.configuration.lettuce.pubsub.annotation.MessageChannel;
import io.micronaut.configuration.lettuce.pubsub.annotation.RedisListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.UUID;

/**
 * Everything goes through Redis, this instance's own events included: one path and one order, whichever instance an event came from.
 */
@RedisListener
@Slf4j
@RequiredArgsConstructor
class RoomFeedImpl implements RoomFeed {

    static final String CHANNEL = "voiceroom.feed";

    private final RoomFeedBus bus;
    private final Sinks.Many<RoomFeedEvent> heard = Sinks.many().multicast().directBestEffort();

    @MessageChannel(CHANNEL)
    void receive(@MessageBody RoomFeedEvent event) {
        heard.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
    }

    @Override
    public Publisher<RoomFeedEvent> changes() {
        return heard.asFlux();
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
        bus.publish(event).exceptionally(e -> {
            log.warn("Could not tell the hallways about {}", event.type(), e);
            return 0L;
        });
    }
}
