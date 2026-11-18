package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import io.micronaut.configuration.lettuce.pubsub.annotation.MessageChannel;
import io.micronaut.configuration.lettuce.pubsub.annotation.RedisPubSubClient;

import java.util.concurrent.CompletionStage;

/**
 * Publishes to the Redis channel every instance listens on; Micronaut writes the implementation and encodes the event as JSON. It does not
 * wait for Redis: the answer (how many instances received it) comes later, and messages sent together share a round trip.
 */
@RedisPubSubClient
interface RoomFeedBus {

    @MessageChannel(RoomFeedImpl.CHANNEL)
    CompletionStage<Long> publish(RoomFeedEvent event);
}
