package com.jtalk.livecommunication.domain.validator;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.jtalk.livecommunication.support.ProblemAssert.assertProblem;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** The whisper preconditions against real Redis state: who is seated, who is present. */
@MicronautTest
class WhisperValidatorTest {

    private final UUID roomId = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private final UUID listener = UUID.randomUUID();

    @Inject
    WhisperValidator whisper;

    @Inject
    StageRepository stage;

    @Inject
    PresenceRepository presence;

    @BeforeEach
    void seatTheHostAndLetBothBeInTheRoom() {
        stage.initializeStage(roomId, host);
        presence.touch(roomId, host);
        presence.touch(roomId, listener);
    }

    private VoiceRoom room(boolean allowWhisper) {
        return new VoiceRoom(roomId, "Whisper rules", null, host, "en", "ar", 4, allowWhisper, VoiceRoomStatus.ACTIVE, null, null);
    }

    @Test
    void aSeatedSpeakerMayWhisperToSomeoneInTheRoom() {
        assertDoesNotThrow(() -> whisper.requireAllowed(room(true), host, listener));
    }

    @Test
    void aRoomWithWhisperingSwitchedOffRefusesBeforeAnythingElseIsLookedAt() {
        assertProblem(HttpStatus.FORBIDDEN, "whisper-not-permitted", "disabled in this room", () -> whisper.requireAllowed(room(false), host, host));
    }

    @Test
    void onlySeatedSpeakersMayWhisper() {
        assertProblem(HttpStatus.FORBIDDEN, "whisper-not-permitted", "Only stage speakers", () -> whisper.requireAllowed(room(true), listener, host));
    }

    @Test
    void youCannotWhisperToYourself() {
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "whisper-to-self", "yourself", () -> whisper.requireAllowed(room(true), host, host));
    }

    @Test
    void theOtherPersonMustBeInTheRoom() {
        UUID stranger = UUID.randomUUID();

        assertProblem(HttpStatus.NOT_FOUND, "whisper-target-not-present", stranger.toString(), () -> whisper.requireAllowed(room(true), host, stranger));
    }
}
