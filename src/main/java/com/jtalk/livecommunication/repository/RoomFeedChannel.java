package com.jtalk.livecommunication.repository;

import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Redis channel the room feed travels on, so every instance tells the hallways connected to it what happened on any instance.
 * <p>
 * An instance is subscribed only while it has a hallway to tell: a managed Redis bills for every message delivered, so an instance with
 * nobody watching receives nothing. Instances that are watching sign up in a small sorted set (scored by when the sign-up lapses, renewed
 * while they watch), which lets whoever produces the numbers on the cards skip that work when no instance is watching.
 */
@Singleton
@Slf4j
public class RoomFeedChannel {

    private static final String CHANNEL = "voiceroom:feed";
    private static final String WATCHERS = "voiceroom:feed:watchers";
    private static final Duration SIGN_UP_LASTS = Duration.ofSeconds(30);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final Clock clock;
    private final String instance = UUID.randomUUID().toString();

    private final Object lock = new Object();
    private int hallways;
    private CompletableFuture<Void> subscribed = CompletableFuture.completedFuture(null);
    private StatefulRedisPubSubConnection<String, String> pubSub;

    private volatile Consumer<String> receiver = message -> {
    };
    private volatile Runnable reconnected = () -> {
    };

    public RoomFeedChannel(RedisClient client, StatefulRedisConnection<String, String> connection, Clock clock) {
        this.client = client;
        this.connection = connection;
        this.clock = clock;
    }

    /** Who hears the messages of the channel. */
    public void receiver(Consumer<String> receiver) {
        this.receiver = receiver;
    }

    /** What to do when the connection to Redis came back: messages in between were missed, so hallways must load their list again. */
    public void onReconnected(Runnable reconnected) {
        this.reconnected = reconnected;
    }

    @EventListener
    void onStartup(ServerStartupEvent event) {
        pubSub = client.connectPubSub();
        pubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                receiver.accept(message);
            }
        });
        pubSub.addListener(new RedisConnectionStateListener() {
            @Override
            public void onRedisConnected(io.lettuce.core.RedisChannelHandler<?, ?> connection, java.net.SocketAddress socketAddress) {
                reconnected.run();
            }
        });
    }

    /**
     * A hallway starts watching. The first one on this instance subscribes to the channel; the future completes once Redis has confirmed it,
     * so a hallway told it is connected misses nothing from then on.
     */
    public CompletableFuture<Void> listen() {
        synchronized (lock) {
            if (hallways++ == 0) {
                subscribed = pubSub.async().subscribe(CHANNEL).toCompletableFuture();
                signUp();
            }
            return subscribed;
        }
    }

    /** A hallway stops watching. The last one on this instance unsubscribes and signs off. */
    public void stopListening() {
        synchronized (lock) {
            if (--hallways == 0) {
                pubSub.async().unsubscribe(CHANNEL);
                connection.async().zrem(WATCHERS, instance);
            }
        }
    }

    /** Sends a message to every instance that is subscribed, this one included. Returns how many received it. */
    public long publish(String message) {
        return connection.sync().publish(CHANNEL, message);
    }

    /** Whether any instance has a hallway watching right now. */
    public boolean anyoneWatching() {
        return connection.sync().zcount(WATCHERS, Range.create((double) clock.millis(), Double.POSITIVE_INFINITY)) > 0;
    }

    @Scheduled(fixedDelay = "10s")
    void renewSignUp() {
        boolean watching;
        synchronized (lock) {
            watching = hallways > 0;
        }
        try {
            if (watching) {
                signUp();
            }
            connection.async().zremrangebyscore(WATCHERS, Range.create(Double.NEGATIVE_INFINITY, (double) clock.millis()));
        } catch (RuntimeException e) {
            log.warn("Could not renew the room feed sign-up", e);
        }
    }

    private void signUp() {
        connection.async().zadd(WATCHERS, (double) clock.instant().plus(SIGN_UP_LASTS).toEpochMilli(), instance);
    }

    @PreDestroy
    void close() {
        if (pubSub != null) {
            pubSub.close();
        }
    }
}
