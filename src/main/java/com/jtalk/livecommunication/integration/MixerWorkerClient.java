package com.jtalk.livecommunication.integration;

import com.jtalk.livecommunication.config.MixerProperties;
import com.jtalk.livecommunication.domain.model.MixerAssignment;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;

import java.util.Map;

/** Producer side of the {@code mixer:assignments} Redis Stream consumed by the mixer workers. */
@Singleton
public class MixerWorkerClient {

    private static final long MAX_STREAM_LENGTH = 10_000;

    private final StatefulRedisConnection<String, String> connection;
    private final String stream;

    public MixerWorkerClient(StatefulRedisConnection<String, String> connection, MixerProperties properties) {
        this.connection = connection;
        this.stream = properties.stream();
    }

    public void publish(MixerAssignment assignment) {
        connection.sync().xadd(stream, XAddArgs.Builder.maxlen(MAX_STREAM_LENGTH).approximateTrimming(), Map.of(
                "roomId", assignment.roomId().toString(),
                "mode", assignment.mode().name(),
                "action", assignment.action().name()));
    }
}
