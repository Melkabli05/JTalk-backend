package com.jtalk.livecommunication.integration.impl;

import com.jtalk.livecommunication.config.LiveKitProperties;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.model.RoomRealtimeEvent;
import com.jtalk.livecommunication.exception.ProblemType;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
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
import livekit.LivekitModels.TrackSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    /** The name of the microphone source in a join token's canPublishSources grant. */
    private static final String MICROPHONE_SOURCE = "microphone";

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
    public String serverUrl() {
        return properties.url();
    }

    @Override
    public String generateToken(UUID roomId, UUID userId, boolean canPublish) {
        return token(roomId.toString(), userId, canPublish, true);
    }

    @Override
    public void setPublishPermission(UUID roomId, UUID userId, boolean canPublish) {
        // The media server enforces this, not the client: a speaker may publish a microphone and nothing else (no camera or screen
        // share into an audio room), and nobody may write to the data channel: room events are sent by this service alone.
        var permission = ParticipantPermission.newBuilder()
                .setCanSubscribe(true)
                .setCanPublish(canPublish)
                .addAllCanPublishSources(canPublish ? List.of(TrackSource.MICROPHONE) : List.of())
                .setCanPublishData(false)
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
        // revokeTokenTs is Unix SECONDS: LiveKit rejects anything not within 60 s of its own clock, so milliseconds fail.
        execute(roomService.removeParticipant(roomId.toString(), userId.toString(), clock.instant().getEpochSecond()), "remove participant");
    }

    @Override
    public void broadcastRoomEvent(UUID roomId, RoomEventType type, UUID targetUserId) {
        try {
            RoomRealtimeEvent event = new RoomRealtimeEvent(type, roomId, targetUserId, clock.instant());
            // SDK parameter order: destination SIDs, then destination identities; both empty means everyone in the room.
            execute(roomService.sendData(roomId.toString(), toJson(event), DataPacket.Kind.RELIABLE, List.of(), List.of(), ROOM_EVENTS_TOPIC),
                    "send data message");
        } catch (RuntimeException e) {
            log.warn("Could not broadcast {} to room {}", type, roomId, e);
        }
    }

    private byte[] toJson(Object message) {
        try {
            return json.writeValueAsBytes(message);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String token(String roomName, UUID userId, boolean canPublish, boolean canSubscribe) {
        var token = new AccessToken(properties.apiKey(), properties.apiSecret());
        token.setIdentity(userId.toString());
        token.setExpiration(Date.from(clock.instant().plus(properties.tokenTtl())));
        // Enforced by the media server whatever a modified client does with the token: listeners cannot publish at all, speakers only a
        // microphone, and no one can write to the data channel (room events are sent by this service alone, so a client cannot fake one).
        token.addGrants(
                new RoomJoin(true),
                new RoomName(roomName),
                new CanSubscribe(canSubscribe),
                new CanPublish(canPublish),
                new CanPublishSources(canPublish ? List.of(MICROPHONE_SOURCE) : List.of()),
                new CanPublishData(false));
        return token.toJwt();
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
