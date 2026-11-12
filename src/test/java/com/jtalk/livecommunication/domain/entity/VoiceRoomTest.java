package com.jtalk.livecommunication.domain.entity;

import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceRoomTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void opensActiveRoomWithDefaultSeats() {
        VoiceRoom room = VoiceRoom.open("  English practice ", "host-1", null, NOW);

        assertEquals("English practice", room.name());
        assertEquals(VoiceRoom.DEFAULT_SPEAKERS, room.maxSpeakers());
        assertEquals(VoiceRoomStatus.ACTIVE, room.status());
        assertNotNull(room.id());
        assertTrue(room.isActive());
        assertTrue(room.isHost("host-1"));
        assertFalse(room.isHost("someone-else"));
    }

    @Test
    void closingRecordsTimeAndDeactivates() {
        VoiceRoom closed = VoiceRoom.open("room", "host", 4, NOW).close(NOW.plusSeconds(60));

        assertEquals(VoiceRoomStatus.CLOSED, closed.status());
        assertEquals(NOW.plusSeconds(60), closed.closedAt());
        assertFalse(closed.isActive());
    }
}
