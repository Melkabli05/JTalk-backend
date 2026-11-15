package com.jtalk.livecommunication.dev;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/**
 * A named test user with a fixed id, so the seeded rooms and the test page agree on who is who. The languages are
 * what a language-exchange profile shows: what they speak natively and what they are learning. The avatar is the name of one
 * of the pictures the client bundles, or empty.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable.Serializable
public record DevUser(UUID id, String name, String nativeLanguage, String learning, String avatar) {

    public static final DevUser ALICE = of("Alice", "es", "en", "avatar-01");
    public static final DevUser BOB = of("Bob", "en", "ar", "avatar-02");
    public static final DevUser CAROL = of("Carol", "ar", "en", "avatar-03");
    public static final DevUser DINA = of("Dina", "fr", "en", "avatar-08");
    public static final DevUser OMAR = of("Omar", "ar", "fr", "avatar-07");
    public static final DevUser MAYA = of("Maya", "es", "ja", "avatar-09");
    public static final DevUser LUCAS = of("Lucas", "pt", "es", "avatar-05");
    public static final DevUser SOFIA = of("Sofia", "pt", "fr", "avatar-06");
    public static final DevUser YUKI = of("Yuki", "ja", "en", "avatar-04");
    public static final DevUser HANS = of("Hans", "de", "en", "avatar-10");
    public static final DevUser PRIYA = of("Priya", "hi", "en", "avatar-03");

    public static final List<DevUser> ALL = List.of(ALICE, BOB, CAROL, DINA, OMAR, MAYA, LUCAS, SOFIA, YUKI, HANS, PRIYA);

    private static DevUser of(String name, String nativeLanguage, String learning, String avatar) {
        return new DevUser(UUID.nameUUIDFromBytes(("dev-user:" + name).getBytes()), name, nativeLanguage, learning, avatar);
    }
}
