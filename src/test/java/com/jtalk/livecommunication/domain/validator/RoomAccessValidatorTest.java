package com.jtalk.livecommunication.domain.validator;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.jtalk.livecommunication.support.ProblemAssert.assertProblem;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@MicronautTest
class RoomAccessValidatorTest {

    private final UUID host = UUID.randomUUID();
    private final UUID guest = UUID.randomUUID();
    private final VoiceRoom room = VoiceRoom.open("Rules", null, host, "en", "ar", 4, true);

    @Inject
    RoomAccessValidator access;

    @Inject
    PresenceRepository presence;

    @Test
    void onlyTheHostPassesTheHostCheckAndTheMessageNamesTheAction() {
        assertDoesNotThrow(() -> access.requireHost(room, host, "close this room"));

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", "Only the room host can close this room",
                () -> access.requireHost(room, guest, "close this room"));
    }

    @Test
    void theHostMayKickEveryoneExceptThemselves() {
        assertDoesNotThrow(() -> access.requireCanKick(room, host, guest));

        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", "kick participants", () -> access.requireCanKick(room, guest, host));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "cannot-kick-host", "host cannot be kicked", () -> access.requireCanKick(room, host, host));
    }

    @Test
    void notBeingTheHostIsCheckedBeforeWhoIsBeingKicked() {
        assertProblem(HttpStatus.FORBIDDEN, "not-room-host", "kick participants", () -> access.requireCanKick(room, guest, host));
    }

    @Test
    void theHostKeepsTheirSeatButEveryoneElseMayLeaveTheirs() {
        assertDoesNotThrow(() -> access.requireCanLeaveStage(room, guest));

        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "host-cannot-leave-stage", "keeps their stage seat", () -> access.requireCanLeaveStage(room, host));
    }

    @Test
    void aBannedUserCannotComeBackButAnyoneElseCan() {
        UUID roomId = UUID.randomUUID();
        assertDoesNotThrow(() -> access.requireNotBanned(roomId, guest));

        presence.ban(roomId, guest);

        assertProblem(HttpStatus.FORBIDDEN, "kicked-from-room", guest.toString(), () -> access.requireNotBanned(roomId, guest));
        assertDoesNotThrow(() -> access.requireNotBanned(roomId, host));
    }

    @Test
    void anAdmissionDeniedByAnotherRedisRoundTripIsTheSameProblem() {
        assertDoesNotThrow(() -> access.requireAdmitted(true, guest));

        assertProblem(HttpStatus.FORBIDDEN, "kicked-from-room", "removed from this room", () -> access.requireAdmitted(false, guest));
    }
}
