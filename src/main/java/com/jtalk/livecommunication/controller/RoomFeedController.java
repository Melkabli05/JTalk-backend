package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import com.jtalk.livecommunication.service.RoomFeed;
import com.jtalk.livecommunication.service.VoiceRoomService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.sse.Event;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.RequiredArgsConstructor;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Controller("/api/v1/voice-rooms/feed")
@Secured(SecurityRule.IS_AUTHENTICATED)
@RequiredArgsConstructor
class RoomFeedController {

    /** How far behind a hallway may fall before it is cut off: it reconnects and starts from a fresh list. */
    private static final int MAX_BACKLOG = 256;
    /** Proxies close a stream that stays silent; a ping now and then keeps it open. */
    private static final Duration PING_EVERY = Duration.ofSeconds(20);

    private final RoomFeed feed;
    private final VoiceRoomService rooms;

    /**
     * The hallway's live view of the open rooms (server-sent events): the open rooms first, then a message whenever a room opens, changes,
     * closes or its numbers move. One request is all a hallway needs, and a reconnect starts again from a fresh list.
     */
    @Get(produces = MediaType.TEXT_EVENT_STREAM)
    Flux<Event<?>> watch() {
        Flux<RoomFeedEvent> listThenChanges = Flux.defer(() -> {
            // Listen before reading the list, so a room that opens in between is replayed after it instead of being lost.
            ConnectableFlux<RoomFeedEvent> missed = Flux.from(feed.changes()).replay(MAX_BACKLOG);
            Disposable listening = missed.connect();
            return Mono.fromCallable(() -> RoomFeedEvent.snapshot(rooms.openRooms()))
                    .subscribeOn(Schedulers.boundedElastic())   // a database read must not block the event loop
                    .concatWith(missed)
                    .doFinally(signal -> listening.dispose());
        });
        Flux<Event<?>> pings = Flux.interval(PING_EVERY).map(tick -> Event.of("").name("ping"));
        return Flux.merge(listThenChanges.map(Event::of), pings).onBackpressureBuffer(MAX_BACKLOG, BufferOverflowStrategy.ERROR);
    }
}
