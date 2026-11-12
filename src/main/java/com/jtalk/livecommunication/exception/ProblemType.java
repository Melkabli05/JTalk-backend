package com.jtalk.livecommunication.exception;

import io.micronaut.http.HttpStatus;
import io.micronaut.problem.HttpStatusType;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import java.net.URI;
import java.util.Locale;

/**
 * Every API error in one place: HTTP status and title are declared once per type, and the RFC 9457
 * {@code type} is derived from the constant's name ({@code STAGE_FULL} -> {@code ...:stage-full}) so clients
 * can switch on a stable code. Micronaut's Problem module renders the thrown result as
 * {@code application/problem+json}.
 * <pre>throw ProblemType.STAGE_FULL.because("All 8 seats are taken");</pre>
 */
public enum ProblemType {
    VOICE_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "Voice room not found"),
    VOICE_ROOM_CLOSED(HttpStatus.CONFLICT, "Voice room is closed"),
    NOT_ROOM_HOST(HttpStatus.FORBIDDEN, "Only the host can do this"),
    STAGE_FULL(HttpStatus.CONFLICT, "Stage is full"),
    ALREADY_ON_STAGE(HttpStatus.CONFLICT, "Already on stage"),
    NOT_IN_HAND_RAISE_QUEUE(HttpStatus.NOT_FOUND, "Hand not raised"),
    HOST_DOES_NOT_NEED_SEAT(HttpStatus.UNPROCESSABLE_ENTITY, "Host does not need a seat"),
    CANNOT_REMOVE_SPEAKER(HttpStatus.FORBIDDEN, "Cannot remove another speaker"),
    WHISPER_NOT_PERMITTED(HttpStatus.FORBIDDEN, "Whisper not permitted"),
    WHISPER_TO_SELF(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot whisper to yourself"),
    WHISPER_TARGET_NOT_PRESENT(HttpStatus.NOT_FOUND, "Whisper target not in the room"),
    INVALID_WEBHOOK_SIGNATURE(HttpStatus.UNAUTHORIZED, "Invalid webhook signature"),
    MEDIA_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "Media server rejected the request"),
    MEDIA_SERVER_UNREACHABLE(HttpStatus.BAD_GATEWAY, "Media server unreachable");

    private static final String TYPE_PREFIX = "urn:jtalk:live-communication:problem:";

    private final HttpStatus status;
    private final String title;
    private final URI type;

    ProblemType(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
        this.type = URI.create(TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public ThrowableProblem because(String detail) {
        return Problem.builder()
                .withType(type)
                .withTitle(title)
                .withStatus(new HttpStatusType(status))
                .withDetail(detail)
                .build();
    }
}
