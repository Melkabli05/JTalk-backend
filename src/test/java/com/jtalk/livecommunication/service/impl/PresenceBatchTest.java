package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.repository.LiveStatsRepository;
import com.jtalk.livecommunication.support.ApiTestBase;
import com.jtalk.livecommunication.support.RedisCommandRecorder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who came and went is announced every couple of seconds, for every room that changed, from one Redis read; the hallways hear the numbers too. */
class PresenceBatchTest extends ApiTestBase {

    @Inject
    RoomEvents events;

    @Inject
    RedisCommandRecorder redisCommands;

    @Inject
    LiveStatsRepository liveStats;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(super.getProperties());
        properties.put("voice-room.presence-batch", "1h");   // the test runs the batch itself
        return properties;
    }

    @Test
    void manyRoomsChangingAtOnceAreAnnouncedFromOneRead() {
        List<String> rooms = List.of("a", "b", "c", "d", "e").stream().map(name -> createRoom("host-" + name, "Room " + name, 4)).toList();
        for (String room : rooms) {
            join(room, "guest");
        }
        events.sendPresenceChanges();   // announce what the joins above changed, so the next batch starts from nothing
        gateway.presenceEvents.clear();
        for (String room : rooms) {
            join(room, "latecomer");
        }

        var trace = redisCommands.trace(() -> {
            events.sendPresenceChanges();
            pause(300);   // telling the hallways does not wait for Redis: give it time to be sent
            return null;
        });

        assertEquals(5 * 3, trace.commands().stream().filter(command -> !command.equals("PUBLISH")).count(),
                "the stage, the queue and the presence count of each room");
        assertEquals(5, trace.count("PUBLISH"), "and one message to the hallways per room");
        assertEquals(5, gateway.presenceEvents.stream().map(event -> event.roomId()).distinct().count(), "and every room still hears about its own change");
        assertTrue(gateway.presenceEvents.stream().allMatch(event -> event.presence().joined().equals(List.of(id("latecomer")))),
                "each announcement names who came since the last one");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void theNumbersOfManyRoomsAreReadInOneRoundTrip() {
        List<java.util.UUID> rooms = List.of("a", "b", "c", "d", "e").stream().map(name -> uuid(createRoom("host-" + name, "Room " + name, 4))).toList();

        var trace = redisCommands.trace(() -> liveStats.find(rooms));

        assertEquals(5 * 3, trace.commands().size());
        assertEquals(1, trace.roundTrips(), "all five rooms in one round trip, not one each: " + trace);
    }
}
