package com.jtalk.livecommunication;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a deployment depends on in the "cloud" profile, read from its configuration without starting the application. */
class CloudProfileTest {

    private static Environment cloud() {
        return ApplicationContext.builder("cloud").deduceEnvironment(false).build().getEnvironment().start();
    }

    @Test
    void bindsThePortTheHostGives() {
        String before = System.getProperty("PORT");
        System.setProperty("PORT", "10000");   // what Render sets: the port its health checks and traffic are sent to
        try {
            assertEquals(Optional.of(10000), cloud().getProperty("micronaut.server.port", Integer.class));
        } finally {
            if (before == null) {
                System.clearProperty("PORT");
            } else {
                System.setProperty("PORT", before);
            }
        }
    }

    @Test
    void bindsEightyEightyWhenTheHostGivesNone() {
        assertEquals(Optional.of(8080), cloud().getProperty("micronaut.server.port", Integer.class));
    }

    @Test
    void openTestingModeIsTheCloudServicesWithSecurityOff() {
        // MICRONAUT_ENVIRONMENTS=cloud,dev, which render.yaml sets until a user service issues tokens
        Environment open = ApplicationContext.builder("cloud", "dev").deduceEnvironment(false).build().getEnvironment().start();

        assertEquals(Optional.of(false), open.getProperty("micronaut.security.enabled", Boolean.class));
        assertTrue(open.getProperty("datasources.default.url", String.class).orElse("").contains("neon.tech"));
        assertTrue(cloud().getProperty("micronaut.security.enabled", Boolean.class).isEmpty(), "the cloud profile alone stays secure");
    }

    @Test
    void usesTheHostedServices() {
        Environment cloud = cloud();

        assertTrue(cloud.getProperty("datasources.default.url", String.class).orElse("").contains("neon.tech"));
        assertTrue(cloud.getProperty("redis.uri", String.class).orElse("").startsWith("rediss://"), "Redis over TLS");
    }
}
