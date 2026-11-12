package com.jtalk.livecommunication.mixer;

import com.jtalk.livecommunication.domain.enums.MixerAction;
import com.jtalk.livecommunication.domain.enums.MixerMode;
import com.jtalk.livecommunication.domain.model.MixerAssignment;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.lettuce.core.api.StatefulRedisConnection;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;

/**
 * Media Plane entry point (run with {@code ./gradlew runMixer}). Plain Java, no Micronaut: it consumes
 * mixer assignments from the Redis Stream and mixes rooms off-heap.
 * <p>
 * Configuration (environment): {@code REDIS_URI} (default redis://localhost:6379), {@code MIXER_STREAM}
 * (default mixer:assignments), {@code MIXER_GROUP} (default mixers). At least one {@link RoomAudioTransport}
 * must be on the classpath (registered with {@link ServiceLoader}); without one the worker refuses to start,
 * because it could acknowledge assignments it can never carry out.
 */
public final class MixerWorkerApplication {

    private MixerWorkerApplication() {
    }

    public static void main(String[] args) {
        List<RoomAudioTransport> transports = new ArrayList<>();
        ServiceLoader.load(RoomAudioTransport.class).forEach(transports::add);
        if (transports.isEmpty()) {
            System.err.println("No RoomAudioTransport found on the classpath. LiveKit has no Java realtime SDK, so a "
                    + "transport (WebRTC for FALLBACK, MoQ/LL-HLS for BROADCAST) has to be provided and registered via "
                    + "META-INF/services/" + RoomAudioTransport.class.getName());
            System.exit(1);
        }
        String stream = env("MIXER_STREAM", "mixer:assignments");
        String group = env("MIXER_GROUP", "mixers");
        String consumer = ManagementFactory.getRuntimeMXBean().getName();   // pid@host

        RedisClient client = RedisClient.create(env("REDIS_URI", "redis://localhost:6379"));
        try (StatefulRedisConnection<String, String> connection = client.connect();
             MixerWorker worker = new MixerWorker(transports, NativeOpusEncoder::new)) {
            createGroup(connection, stream, group);
            System.out.println("Mixer worker " + consumer + " consuming " + stream + " as group " + group);
            while (!Thread.currentThread().isInterrupted()) {
                List<StreamMessage<String, String>> messages = connection.sync().xreadgroup(
                        Consumer.from(group, consumer),
                        XReadArgs.Builder.block(5_000).count(10),
                        StreamOffset.lastConsumed(stream));
                for (StreamMessage<String, String> message : messages) {
                    worker.handle(parse(message));
                    connection.sync().xack(stream, group, message.getId());
                }
            }
        } finally {
            client.shutdown();
        }
    }

    static MixerAssignment parse(StreamMessage<String, String> message) {
        var body = message.getBody();
        return new MixerAssignment(
                UUID.fromString(body.get("roomId")),
                MixerMode.valueOf(body.get("mode")),
                MixerAction.valueOf(body.get("action")));
    }

    private static void createGroup(StatefulRedisConnection<String, String> connection, String stream, String group) {
        try {
            connection.sync().xgroupCreate(StreamOffset.latest(stream), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisBusyException alreadyExists) {
            // another worker created it first
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
