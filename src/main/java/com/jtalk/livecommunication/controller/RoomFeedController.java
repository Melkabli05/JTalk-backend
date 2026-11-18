package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.service.RoomFeed;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.sse.Event;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Controller("/api/v1/voice-rooms/feed")
@Secured(SecurityRule.IS_AUTHENTICATED)
@RequiredArgsConstructor
class RoomFeedController {

    /** Proxies close a stream that stays silent; a ping now and then keeps it open. */
    private static final Duration PING_EVERY = Duration.ofSeconds(20);

    private final RoomFeed feed;

    /**
     * The hallway's live view of the open rooms (server-sent events): a {@code READY} first, then a message whenever a room opens, changes,
     * closes or its numbers move. The client fetches the list once after {@code READY} and applies these on top.
     */
    @Get(produces = MediaType.TEXT_EVENT_STREAM)
    Flux<Event<?>> watch() {
        Flux<Event<?>> changes = Flux.from(feed.subscribe()).map(Event::of);
        Flux<Event<?>> pings = Flux.interval(PING_EVERY).map(tick -> Event.of("").name("ping"));
        return Flux.merge(changes, pings);
    }
}
