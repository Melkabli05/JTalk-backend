package com.jtalk.livecommunication.dev;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/**
 * A named test user with a fixed id, so the seeded rooms and the test page agree on who is who. The languages are
 * what a language-exchange profile shows: what they speak natively and what they are learning.
 */
@Serdeable.Serializable
public record DevUser(UUID id, String name, String nativeLanguage, String learning) {

    public static final DevUser ALICE = of("Alice", "es", "en");
    public static final DevUser BOB = of("Bob", "en", "ar");
    public static final DevUser CAROL = of("Carol", "ar", "en");
    public static final DevUser DINA = of("Dina", "fr", "en");
    public static final DevUser OMAR = of("Omar", "ar", "fr");
    public static final DevUser MAYA = of("Maya", "es", "ja");
    public static final DevUser LUCAS = of("Lucas", "pt", "es");
    public static final DevUser SOFIA = of("Sofia", "pt", "fr");
    public static final DevUser YUKI = of("Yuki", "ja", "en");
    public static final DevUser HANS = of("Hans", "de", "en");
    public static final DevUser PRIYA = of("Priya", "hi", "en");

    public static final List<DevUser> ALL = List.of(ALICE, BOB, CAROL, DINA, OMAR, MAYA, LUCAS, SOFIA, YUKI, HANS, PRIYA);

    private static DevUser of(String name, String nativeLanguage, String learning) {
        return new DevUser(UUID.nameUUIDFromBytes(("dev-user:" + name).getBytes()), name, nativeLanguage, learning);
    }
}
