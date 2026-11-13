package com.jtalk.livecommunication.dev;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/** A named test user with a fixed id, so the seeded rooms and the test page agree on who is who. */
@Serdeable.Serializable
public record DevUser(UUID id, String name) {

    public static final DevUser ALICE = of("Alice");
    public static final DevUser BOB = of("Bob");
    public static final DevUser CAROL = of("Carol");

    public static final List<DevUser> ALL = List.of(ALICE, BOB, CAROL);

    private static DevUser of(String name) {
        return new DevUser(UUID.nameUUIDFromBytes(("dev-user:" + name).getBytes()), name);
    }
}
