package com.jtalk.livecommunication.config;

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
    NOT_ROOM_HOST(HttpStatus.FORBIDDEN, "Only the host can do this"),
    NOT_ROOM_MODERATOR(HttpStatus.FORBIDDEN, "Only the host or a moderator can do this"),
    CANNOT_RAISE_HAND(HttpStatus.FORBIDDEN, "Hosts and moderators cannot raise hand"),
    MODERATOR_LIMIT_REACHED(HttpStatus.CONFLICT, "Moderator limit reached"),
    STAGE_FULL(HttpStatus.CONFLICT, "Stage is full"),
    ALREADY_ON_STAGE(HttpStatus.CONFLICT, "Already on stage"),
    HOST_CANNOT_LEAVE_STAGE(HttpStatus.FORBIDDEN, "The host keeps their stage seat"),
    CANNOT_KICK_HOST(HttpStatus.UNPROCESSABLE_ENTITY, "The host cannot be kicked"),
    KICKED_FROM_ROOM(HttpStatus.FORBIDDEN, "Removed from this room"),
    NOT_IN_ROOM(HttpStatus.FORBIDDEN, "Join the room first"),
    REACTING_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "Reacting too fast"),
    INVALID_COMMENT(HttpStatus.BAD_REQUEST, "Not a comment the room can take"),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "Invalid input"),
    CHAT_MUTED(HttpStatus.FORBIDDEN, "Muted in the chat"),
    COMMENTING_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "Commenting too fast"),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Comment not found"),
    CANNOT_MUTE_HOST(HttpStatus.UNPROCESSABLE_ENTITY, "The host cannot be muted in their own chat"),
    INVALID_WEBHOOK_SIGNATURE(HttpStatus.UNAUTHORIZED, "Invalid webhook signature"),
    MEDIA_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "Media server rejected the request"),
    MEDIA_SERVER_UNREACHABLE(HttpStatus.BAD_GATEWAY, "Media server unreachable"),
    NOT_A_PARTICIPANT(HttpStatus.CONFLICT, "User is not a participant in this room"),
    CANNOT_TRANSFER_HOST_TO_SELF(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot transfer host to yourself"),
    HOST_SUCCESSOR_REQUIRED(HttpStatus.CONFLICT, "Choose a participant to take over as host before leaving"),
    SUCCESSOR_NOT_IN_ROOM(HttpStatus.CONFLICT, "That participant is no longer in the room");

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
