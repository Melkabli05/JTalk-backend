package com.jtalk.livecommunication.dev;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.List;

/** Tells the test page who the seeded users are. Exists only in the dev profile. */
@Controller("/dev")
@Requires(env = Environment.DEVELOPMENT)
class DevController {

    @Get("/users")
    List<DevUser> users() {
        return DevUser.ALL;
    }
}
