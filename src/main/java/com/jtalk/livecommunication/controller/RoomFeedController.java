package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.enums.RoomFeedEventType;
import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.service.RoomFeed;
import com.jtalk.livecommunication.service.VoiceRoomService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.inject.Named;
import jakarta.validation.constraints.Size;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Controller("/api/v1/voice-rooms/feed")
@Secured(SecurityRule.IS_AUTHENTICATED)
@Validated
class RoomFeedController {

    /** How far behind a hallway may fall before it is cut off: it reconnects and starts from a fresh list. */
    private static final int MAX_BACKLOG = 256;
    /** Proxies close a stream that stays silent; a ping now and then keeps it open. */
    private static final Duration PING_EVERY = Duration.ofSeconds(20);

    private final RoomFeed feed;
    private final VoiceRoomService rooms;
    private final Scheduler blocking;

    RoomFeedController(RoomFeed feed, VoiceRoomService rooms, @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.feed = feed;
        this.rooms = rooms;
        this.blocking = Schedulers.fromExecutor(executor);
    }

    /**
     * The hallway's live view of the open rooms (server-sent events): the open rooms first, then a message whenever a room opens, changes,
     * closes or its numbers move. One request is all a hallway needs, and a reconnect starts again from a fresh list.
     */
    @Get(produces = MediaType.TEXT_EVENT_STREAM)
    Flux<Event<?>> watch(@Nullable @QueryValue @Size(max = 10) String targetLanguage) {
        String filter = targetLanguage == null || targetLanguage.isBlank() ? null : targetLanguage.trim();
        Flux<RoomFeedEvent> listThenChanges = Flux.defer(() -> {
            Set<UUID> matchingRoomIds = ConcurrentHashMap.newKeySet();
            // Listen before reading the list, so a room that opens in between is replayed after it instead of being lost.
            ConnectableFlux<RoomFeedEvent> missed = Flux.from(feed.changes()).replay(MAX_BACKLOG);
            Disposable listening = missed.connect();
            return Mono.fromCallable(() -> {
                        List<VoiceRoomResponse> open = rooms.openRooms(filter);
                        if (filter != null) {
                            open.forEach(room -> matchingRoomIds.add(room.id()));
                        }
                        return RoomFeedEvent.snapshot(open);
                    })
                    .subscribeOn(blocking)
                    .concatWith(missed.filter(event -> matchesFilter(event, filter, matchingRoomIds)))
                    .doFinally(signal -> listening.dispose());
        });
        Flux<Event<?>> pings = Flux.interval(PING_EVERY).map(tick -> Event.of("").name("ping"));
        return Flux.merge(listThenChanges.map(Event::of), pings).onBackpressureBuffer(MAX_BACKLOG, BufferOverflowStrategy.ERROR);
    }

    private static boolean matchesFilter(RoomFeedEvent event, @Nullable String filter, Set<UUID> matchingRoomIds) {
        if (filter == null) {
            return true;
        }
        if (event.type() == RoomFeedEventType.ROOM_OPENED || event.type() == RoomFeedEventType.ROOM_UPDATED) {
            VoiceRoomResponse room = event.room();
            if (room != null && filter.equalsIgnoreCase(room.targetLanguage())) {
                matchingRoomIds.add(room.id());
                return true;
            }
            if (event.roomId() != null) {
                matchingRoomIds.remove(event.roomId());
            }
            return false;
        }
        if (event.type() == RoomFeedEventType.ROOM_CLOSED) {
            return event.roomId() != null && matchingRoomIds.remove(event.roomId());
        }
        return event.roomId() != null && matchingRoomIds.contains(event.roomId());
    }
}
