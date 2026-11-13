package com.jtalk.livecommunication.integration.impl;

import com.jtalk.livecommunication.config.LiveKitProperties;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.exception.ProblemType;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.ServerError;
import io.micronaut.http.HttpStatus;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import livekit.LivekitModels.DataPacket;
import livekit.LivekitModels.ParticipantPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * {@link LiveKitGateway} backed by LiveKit access tokens and the Room Service API. The SDK returns a Retrofit {@code Call}
 * that sends nothing until it is executed, so every operation goes through {@link #execute}.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
class LiveKitGatewayImpl implements LiveKitGateway {

    private static final String ROOM_EVENTS_TOPIC = "room-events";

    private final LiveKitProperties properties;
    private final RoomServiceClient roomService;
    private final Clock clock;
    private final ObjectMapper json;

    @Override
    public void createRoom(UUID roomId) {
        // name, emptyTimeout, maxParticipants, nodeId, metadata, minPlayoutDelay, maxPlayoutDelay, syncStreams, departureTimeout
        execute(roomService.createRoom(roomId.toString(), properties.emptyRoomTimeoutSeconds(), null, null, null, null, null, null, null),
                "create room");
    }

    @Override
    public void deleteRoom(UUID roomId) {
        execute(roomService.deleteRoom(roomId.toString()), "delete room");
    }

    @Override
    public String generateToken(UUID roomId, UUID userId, boolean canPublish) {
        return token(roomId.toString(), userId, canPublish, true);
    }

    @Override
    public void setPublishPermission(UUID roomId, UUID userId, boolean canPublish) {
        var permission = ParticipantPermission.newBuilder()
                .setCanSubscribe(true)
                .setCanPublish(canPublish)
                .setCanPublishData(canPublish)
                .build();
        // 404 = participant not connected right now; the permission then applies via the token issued on join.
        execute(roomService.updateParticipant(roomId.toString(), userId.toString(), null, null, permission),
                "update participant permission");
    }

    @Override
    public void updateSubscriptions(UUID roomId, UUID userId, List<String> trackSids, boolean subscribe) {
        if (trackSids.isEmpty()) {
            return;
        }
        execute(roomService.updateSubscriptions(roomId.toString(), userId.toString(), trackSids, subscribe), "update subscriptions");
    }

    @Override
    public void removeParticipant(UUID roomId, UUID userId) {
        execute(roomService.removeParticipant(roomId.toString(), userId.toString(), clock.millis()), "remove participant");
    }

    @Override
    public String generateWhisperToken(UUID roomId, UUID speakerId, UUID targetId, UUID userId) {
        boolean speaker = userId.equals(speakerId);
        return token(whisperRoom(roomId, speakerId, targetId), userId, speaker, !speaker);
    }

    @Override
    public void deleteWhisperRoom(UUID roomId, UUID speakerId, UUID targetId) {
        execute(roomService.deleteRoom(whisperRoom(roomId, speakerId, targetId)), "delete whisper room");
    }

    @Override
    public void sendData(UUID roomId, String topic, byte[] payload, List<UUID> destinationUserIds) {
        // Parameter order in the SDK: destination SIDs first, then destination identities.
        execute(roomService.sendData(roomId.toString(), payload, DataPacket.Kind.RELIABLE, List.of(),
                destinationUserIds.stream().map(UUID::toString).toList(), topic), "send data message");
    }

    @Override
    public void broadcastRoomEvent(UUID roomId, RoomRealtimeEvent event) {
        try {
            sendData(roomId, ROOM_EVENTS_TOPIC, json.writeValueAsBytes(event), List.of());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not broadcast {} to room {}", event.type(), roomId, e);
        }
    }

    private String token(String roomName, UUID userId, boolean canPublish, boolean canSubscribe) {
        var token = new AccessToken(properties.apiKey(), properties.apiSecret());
        token.setIdentity(userId.toString());
        token.setExpiration(Date.from(clock.instant().plus(properties.tokenTtl())));
        token.addGrants(
                new RoomJoin(true),
                new RoomName(roomName),
                new CanSubscribe(canSubscribe),
                new CanPublish(canPublish),
                new CanPublishData(canPublish));
        return token.toJwt();
    }

    /** Not a UUID on purpose: the webhook handler ignores rooms that are not voice rooms. */
    private static String whisperRoom(UUID roomId, UUID speakerId, UUID targetId) {
        return "whisper-" + roomId + "-" + speakerId + "-" + targetId;
    }

    /** Runs the call; a 404 is tolerated (callers treat it as "nothing to do"), other failures become a 502. */
    private static <T> Response<T> execute(Call<T> call, String operation) {
        try {
            Response<T> response = call.execute();
            if (!response.isSuccessful() && response.code() != HttpStatus.NOT_FOUND.getCode()) {
                ServerError error = ServerError.from(response);   // LiveKit's own error code and message
                String reason = error != null ? error.getCode() + ": " + error.getMessage() : "HTTP " + response.code();
                log.warn("LiveKit failed to {}: {}", operation, reason);
                throw ProblemType.MEDIA_SERVER_ERROR.because("LiveKit failed to " + operation + " (" + reason + ")");
            }
            return response;
        } catch (IOException e) {
            log.warn("LiveKit unreachable while trying to {}", operation, e);
            throw ProblemType.MEDIA_SERVER_UNREACHABLE.because("LiveKit unreachable while trying to " + operation);
        }
    }
}
