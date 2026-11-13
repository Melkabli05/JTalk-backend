package com.jtalk.livecommunication.support;

import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.function.Executable;
import org.zalando.problem.ThrowableProblem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Asserts that code throws the service's RFC 9457 problem: its HTTP status, its stable type, and part of its detail. */
public final class ProblemAssert {

    private ProblemAssert() {
    }

    public static void assertProblem(HttpStatus status, String typeSlug, String detailContains, Executable code) {
        ThrowableProblem problem = assertThrows(ThrowableProblem.class, code);
        assertEquals(status.getCode(), problem.getStatus().getStatusCode(), "status");
        assertEquals("urn:jtalk:live-communication:problem:" + typeSlug, problem.getType().toString(), "type");
        assertTrue(problem.getDetail().contains(detailContains), "detail was: " + problem.getDetail());
    }
}
