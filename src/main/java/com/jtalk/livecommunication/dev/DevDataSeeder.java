package com.jtalk.livecommunication.dev;

import com.jtalk.livecommunication.domain.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Gives a fresh development database something realistic to look at: seven language-exchange rooms in different
 * states (a full stage, a queue of raised hands, and a quiet room), filled with eleven named
 * people. It goes through the real services, so PostgreSQL, Redis and LiveKit all agree about what exists (the rooms
 * are real LiveKit rooms). Safe to run on every start: a host who already has an active room gets it back, and the
 * repeated joins, promotions and hand-raises are simply refused and ignored. It runs in the background after startup,
 * so the server is up at once and the rooms fill in over the next few seconds.
 * <p>
 * Presence is heartbeat-based, so a scheduled task keeps the seeded people present, the way their apps would.
 */
@Singleton
@Requires(env = Environment.DEVELOPMENT)
@Slf4j
@RequiredArgsConstructor
class DevDataSeeder {

    /** One room: who hosts it, who is promoted to the stage, who listens, and which listeners have a hand up. */
    private record Plan(DevUser host, String title, String topic, String target, String nativeLanguage, int seats,
                        List<DevUser> speakers, List<DevUser> listeners, List<DevUser> hands) {

        List<DevUser> everyone() {
            return Stream.of(List.of(host), speakers, listeners).flatMap(List::stream).toList();
        }
    }

    private record Member(UUID roomId, UUID userId) {
    }

    private static final List<Plan> PLANS = List.of(
            new Plan(DevUser.ALICE, "Spanish for beginners", "Ordering food and drinks", "es", "en", 6,
                    List.of(DevUser.LUCAS, DevUser.MAYA), List.of(DevUser.BOB, DevUser.CAROL, DevUser.OMAR), List.of(DevUser.BOB)),
            new Plan(DevUser.BOB, "English conversation club", "Talk about your week", "en", "ar", 4,
                    List.of(DevUser.PRIYA), List.of(DevUser.CAROL, DevUser.DINA, DevUser.YUKI, DevUser.HANS), List.of(DevUser.CAROL, DevUser.DINA)),
            new Plan(DevUser.CAROL, "Arabic cafe", "Greetings and small talk", "ar", "en", 8,
                    List.of(DevUser.OMAR), List.of(DevUser.ALICE, DevUser.SOFIA), List.of()),
            new Plan(DevUser.DINA, "French practice: au restaurant", "Booking a table and ordering", "fr", "en", 6,
                    List.of(DevUser.SOFIA), List.of(DevUser.ALICE, DevUser.LUCAS), List.of(DevUser.ALICE)),
            new Plan(DevUser.YUKI, "Japanese N5 study group", "Hiragana warm-up and greetings", "ja", "en", 5,
                    List.of(DevUser.HANS, DevUser.MAYA), List.of(DevUser.BOB, DevUser.PRIYA), List.of()),
            new Plan(DevUser.HANS, "Deutsch am Morgen", "Small talk over coffee (stage is full)", "de", "en", 4,
                    List.of(DevUser.ALICE, DevUser.DINA, DevUser.OMAR), List.of(DevUser.CAROL, DevUser.BOB), List.of(DevUser.BOB)),
            new Plan(DevUser.SOFIA, "Portuguese for travellers", "A quiet room with a single listener", "pt", "en", 6,
                    List.of(), List.of(DevUser.LUCAS), List.of()));

    private final VoiceRoomService rooms;
    private final ParticipantService participants;
    private final StageService stage;

    private final Set<Member> members = ConcurrentHashMap.newKeySet();

    /** In the background: every join and promotion talks to the real LiveKit, so seeding takes a few seconds. */
    @Async
    @EventListener
    void onStartup(StartupEvent event) {
        seed();
    }

    /** Synchronized so a run triggered by hand waits for, and then repeats, the one started at startup. */
    synchronized void seed() {
        PLANS.forEach(this::seedRoom);
        log.info("Dev data: {} rooms, {} people in them", PLANS.size(), members.size());
    }

    private void seedRoom(Plan plan) {
        UUID roomId;
        try {
            roomId = rooms.createRoom(plan.host().id(), new CreateVoiceRoomRequest(plan.title(), plan.topic(), plan.target(),
                    plan.nativeLanguage(), plan.seats())).room().id();
        } catch (RuntimeException e) {
            log.warn("Dev data: could not create room '{}' for {}: {}", plan.title(), plan.host().name(), e.getMessage());
            return;
        }
        // The host is present like everyone else (creating a room does not record presence; their first join does).
        plan.everyone().forEach(user -> attempt(() -> {
            participants.joinRoom(roomId, user.id());
            members.add(new Member(roomId, user.id()));
        }));
        plan.speakers().forEach(user -> attempt(() -> stage.promoteToStage(roomId, plan.host().id(), user.id())));
        plan.hands().forEach(user -> attempt(() -> stage.raiseHand(roomId, user.id())));
    }

    @Scheduled(fixedDelay = "20s", initialDelay = "20s")
    void keepSeededPeoplePresent() {
        members.forEach(member -> attempt(() -> participants.heartbeat(member.roomId(), member.userId())));
    }

    /** Seeding is repeatable, so "already on stage", "room closed" and "removed by the host" are all expected and ignored. */
    private void attempt(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.debug("Dev data step skipped: {}", e.getMessage());
        }
    }
}
