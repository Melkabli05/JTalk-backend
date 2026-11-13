package com.jtalk.livecommunication.domain.entity;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceRoomTest {

    private final UUID host = UUID.randomUUID();

    private VoiceRoom room() {
        return VoiceRoom.open("  English practice ", "Daily life", host, "en", "ar", 8, true);
    }

    @Test
    void opensAnActiveRoomLeavingIdAndTimestampsToTheDatabase() {
        VoiceRoom room = room();

        assertEquals("English practice", room.title());
        assertEquals(VoiceRoomStatus.ACTIVE, room.status());
        assertNull(room.id(), "Micronaut Data assigns the id on insert");
        assertNull(room.createdAt());
        assertTrue(room.isHost(host));
        assertFalse(room.isHost(UUID.randomUUID()));
    }

    @Test
    void partialUpdatesKeepWhatIsNotGiven() {
        VoiceRoom updated = room().withSettings(null, "Travel", 4);

        assertEquals("English practice", updated.title());
        assertEquals("Travel", updated.topic());
        assertEquals(4, updated.maxStageSeats());
        assertEquals("en", updated.targetLanguage(), "language never changes after creation");
    }

    @Test
    void closingMarksTheRoomClosed() {
        assertEquals(VoiceRoomStatus.CLOSED, room().close().status());
    }

    @Test
    void theHostIsAlwaysTheHostWhetherOrNotTheyAreOnStage() {
        assertEquals(ParticipantRole.HOST, room().roleOf(host, true));
        assertEquals(ParticipantRole.HOST, room().roleOf(host, false));
    }

    @Test
    void everyoneElseIsASpeakerOnStageAndAListenerOtherwise() {
        UUID guest = UUID.randomUUID();

        assertEquals(ParticipantRole.SPEAKER, room().roleOf(guest, true));
        assertEquals(ParticipantRole.LISTENER, room().roleOf(guest, false));
    }
}
