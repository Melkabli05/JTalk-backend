package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.repository.LeaseRepository;
import com.jtalk.livecommunication.domain.event.LeaseExpired;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;

/**
 * Listens for Redis key-expiry events and publishes {@link LeaseExpired} when a grace lease runs out; the service layer reclaims the seat.
 * <p>
 * Redis keyspace notifications are fire-and-forget: an event is lost if this instance is down or
 * disconnected at the moment a lease expires. Enabling them needs {@code CONFIG SET}, which managed Redis
 * services often forbid; set {@code notify-keyspace-events} to include {@code Ex} there.
 */
@Singleton
@Slf4j
public class RedisLeaseExpirationListener {

    private static final String NOTIFY_CONFIG = "notify-keyspace-events";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final ApplicationEventPublisher<LeaseExpired> expirations;
    private final ExecutorService executor;
    private StatefulRedisPubSubConnection<String, String> pubSub;

    public RedisLeaseExpirationListener(RedisClient client,
                                   StatefulRedisConnection<String, String> connection,
                                   ApplicationEventPublisher<LeaseExpired> expirations,
                                   @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.client = client;
        this.connection = connection;
        this.expirations = expirations;
        this.executor = executor;
    }

    @EventListener
    void onStartup(ServerStartupEvent event) {
        enableKeyspaceNotifications();
        pubSub = client.connectPubSub();
        pubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String pattern, String channel, String expiredKey) {
                LeaseRepository.parseKey(expiredKey).ifPresent(lease -> executor.execute(() -> report(lease)));
            }
        });
        pubSub.sync().psubscribe("__keyevent@*__:expired");
    }

    private void report(LeaseRepository.LeaseKey lease) {
        try {
            expirations.publishEvent(new LeaseExpired(lease.roomId(), lease.userId()));
        } catch (RuntimeException e) {
            log.error("Failed to reclaim stage seat of {} in room {}", lease.userId(), lease.roomId(), e);
        }
    }

    private void enableKeyspaceNotifications() {
        try {
            var redis = connection.sync();
            String current = redis.configGet(NOTIFY_CONFIG).getOrDefault(NOTIFY_CONFIG, "");
            boolean expiredEventsOn = current.contains("E") && (current.contains("x") || current.contains("A"));
            if (!expiredEventsOn) {
                redis.configSet(NOTIFY_CONFIG, current + "Ex");
            }
        } catch (RuntimeException e) {
            log.warn("Could not enable Redis keyspace notifications; grace leases will not expire until "
                    + "'notify-keyspace-events' includes 'Ex' on the Redis server", e);
        }
    }

    @PreDestroy
    void close() {
        if (pubSub != null) {
            pubSub.close();
        }
    }
}
