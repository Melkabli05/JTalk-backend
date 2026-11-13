package com.jtalk.livecommunication.support;

import io.lettuce.core.RedisClient;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Records the Redis commands the application issues. Lettuce only attaches a command listener to connections created after
 * it is registered, so it is installed here, when Micronaut creates the client and before any connection exists.
 */
@Singleton
public class RedisCommandRecorder implements BeanCreatedEventListener<RedisClient> {

    private final List<String> commands = new CopyOnWriteArrayList<>();
    private volatile boolean recording;

    @Override
    public RedisClient onCreated(BeanCreatedEvent<RedisClient> event) {
        RedisClient client = event.getBean();
        client.addListener(new CommandListener() {
            @Override
            public void commandStarted(CommandStartedEvent started) {
                if (recording) {
                    commands.add(started.getCommand().getType().toString());
                }
            }
        });
        return client;
    }

    /** The Redis commands issued while {@code request} runs. */
    public List<String> during(Supplier<?> request) {
        commands.clear();
        recording = true;
        try {
            request.get();
        } finally {
            recording = false;
        }
        return List.copyOf(commands);
    }
}
