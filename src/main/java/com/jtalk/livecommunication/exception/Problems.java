package com.jtalk.livecommunication.exception;

import io.micronaut.http.HttpStatus;
import io.micronaut.problem.HttpStatusType;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import java.net.URI;

/**
 * Creates RFC 9457 problems that Micronaut's Problem module renders as {@code application/problem+json}:
 * status as HTTP code, reason phrase as title, a stable machine-readable {@code type}, and a human
 * {@code detail}. Throw the result directly.
 */
public final class Problems {

    private static final String TYPE_PREFIX = "urn:jtalk:live-communication:problem:";

    private Problems() {
    }

    /** @param code stable identifier clients can switch on, e.g. {@code stage-full} */
    public static ThrowableProblem of(HttpStatus status, String code, String detail) {
        return Problem.builder()
                .withType(URI.create(TYPE_PREFIX + code))
                .withTitle(status.getReason())
                .withStatus(new HttpStatusType(status))
                .withDetail(detail)
                .build();
    }
}
