package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.repository.LeaseRepository;
import com.jtalk.livecommunication.domain.event.HostLeaseExpired;
import com.jtalk.livecommunication.domain.event.IdleWarningExpired;
import com.jtalk.livecommunication.domain.event.LeaseExpired;
import com.jtalk.livecommunication.domain.event.SoloRoomExpired;
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

import java.util.UUID;
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
    private final ApplicationEventPublisher<HostLeaseExpired> hostExpirations;
    private final ApplicationEventPublisher<SoloRoomExpired> soloExpirations;
    private final ApplicationEventPublisher<IdleWarningExpired> idleExpirations;
    private final ExecutorService executor;
    private StatefulRedisPubSubConnection<String, String> pubSub;

    public RedisLeaseExpirationListener(RedisClient client,
                                   StatefulRedisConnection<String, String> connection,
                                   ApplicationEventPublisher<LeaseExpired> expirations,
                                   ApplicationEventPublisher<HostLeaseExpired> hostExpirations,
                                   ApplicationEventPublisher<SoloRoomExpired> soloExpirations,
                                   ApplicationEventPublisher<IdleWarningExpired> idleExpirations,
                                   @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.client = client;
        this.connection = connection;
        this.expirations = expirations;
        this.hostExpirations = hostExpirations;
        this.soloExpirations = soloExpirations;
        this.idleExpirations = idleExpirations;
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
                LeaseRepository.parseHostLeaseKey(expiredKey)
                        .ifPresent(roomId -> executor.execute(() -> publishSafely(() -> hostExpirations.publishEvent(new HostLeaseExpired(roomId)), roomId)));
                LeaseRepository.parseSoloKey(expiredKey)
                        .ifPresent(roomId -> executor.execute(() -> publishSafely(() -> soloExpirations.publishEvent(new SoloRoomExpired(roomId)), roomId)));
                LeaseRepository.parseIdleWarningKey(expiredKey)
                        .ifPresent(roomId -> executor.execute(() -> publishSafely(() -> idleExpirations.publishEvent(new IdleWarningExpired(roomId)), roomId)));
            }
        });
        pubSub.sync().psubscribe("__keyevent@*__:expired");
    }

    private static void publishSafely(Runnable action, UUID roomId) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Failed to handle Redis expiration for room {}", roomId, e);
        }
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
