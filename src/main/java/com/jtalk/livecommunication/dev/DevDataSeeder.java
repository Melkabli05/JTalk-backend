package com.jtalk.livecommunication.dev;

import com.jtalk.livecommunication.domain.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Gives a fresh development database something to look at. It goes through the real services, so PostgreSQL, Redis
 * and LiveKit all agree about what exists (the rooms are real LiveKit rooms). Safe to run on every start: a host who
 * already has an active room gets it back instead of a second one, and joining or raising a hand twice changes nothing.
 * Presence is heartbeat-based, so the seeded participants drop off after the presence timeout unless the test page
 * keeps them alive.
 */
@Singleton
@Requires(env = Environment.DEVELOPMENT)
@Slf4j
@RequiredArgsConstructor
class DevDataSeeder {

    private final VoiceRoomService rooms;
    private final ParticipantService participants;
    private final StageService stage;

    @EventListener
    void onStartup(StartupEvent event) {
        seed();
    }

    void seed() {
        UUID spanish = openRoom(DevUser.ALICE, "Spanish for beginners", "Ordering food and drinks", "es", "en", 6);
        UUID english = openRoom(DevUser.BOB, "English conversation club", "Talk about your week", "en", "ar", 4);
        openRoom(DevUser.CAROL, "Arabic cafe", "Greetings and small talk", "ar", "en", 8);

        if (spanish != null) {   // Bob and Carol listen in Alice's room; Bob has his hand up
            attempt("seed Alice's room audience", () -> {
                participants.joinRoom(spanish, DevUser.BOB.id());
                participants.joinRoom(spanish, DevUser.CAROL.id());
                stage.raiseHand(spanish, DevUser.BOB.id());
            });
        }
        if (english != null) {   // Carol is waiting for a seat in Bob's room
            attempt("seed Bob's room audience", () -> {
                participants.joinRoom(english, DevUser.CAROL.id());
                stage.raiseHand(english, DevUser.CAROL.id());
            });
        }
    }

    /** Returns the room id, or null if it could not be created (for example the media server is unreachable). */
    private UUID openRoom(DevUser host, String title, String topic, String target, String native_, int seats) {
        try {
            RoomSessionResponse session = rooms.createRoom(host.id(), new CreateVoiceRoomRequest(title, topic, target, native_, seats, true));
            log.info("Dev data: room '{}' hosted by {} ({})", title, host.name(), session.room().id());
            return session.room().id();
        } catch (RuntimeException e) {
            log.warn("Dev data: could not create room '{}' for {}: {}", title, host.name(), e.getMessage());
            return null;
        }
    }

    private void attempt(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("Dev data: could not {}: {}", what, e.getMessage());
        }
    }
}
