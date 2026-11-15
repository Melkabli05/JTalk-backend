package com.jtalk.livecommunication.dev;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Status;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Who the clients call by name, until a real user service exists. The backend knows users by id only, so a client in the
 * dev profile registers the profile its person filled in, and every client reads the list to show names. In memory,
 * forgotten on restart. Exists only in the dev profile.
 */
@Controller("/dev")
@Requires(env = Environment.DEVELOPMENT)
class DevController {

    private final Map<UUID, DevUser> registered = new ConcurrentHashMap<>();
    private final boolean sampleUsers;

    DevController(@Value("${dev.seed-rooms:false}") boolean sampleUsers) {
        this.sampleUsers = sampleUsers;
    }

    /** The registered people, plus the eleven named sample users when sample rooms are switched on. */
    @Get("/users")
    List<DevUser> users() {
        return Stream.concat(sampleUsers ? DevUser.ALL.stream() : Stream.empty(), registered.values().stream()).toList();
    }

    /** Adds or replaces one person. */
    @Post("/users")
    @Status(HttpStatus.NO_CONTENT)
    void register(@Body @Valid DevUserRequest request) {
        registered.put(request.id(), new DevUser(request.id(), request.name().strip(), request.nativeLanguage(), request.learning(),
                request.avatar() == null ? "" : request.avatar().strip()));
    }
}
