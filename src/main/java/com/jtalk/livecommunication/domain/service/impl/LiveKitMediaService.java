package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.config.LiveKitProperties;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.exception.ProblemType;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.ServerError;
import io.micronaut.http.HttpStatus;
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

/** {@link MediaService} backed by LiveKit access tokens and the Room Service API. */
@Singleton
@Slf4j
@RequiredArgsConstructor
class LiveKitMediaService implements MediaService {

    private final LiveKitProperties properties;
    private final RoomServiceClient roomService;
    private final Clock clock;

    @Override
    public MediaConnection issueConnection(UUID roomId, String userId, boolean canPublish) {
        var token = new AccessToken(properties.apiKey(), properties.apiSecret());
        token.setIdentity(userId);
        token.setExpiration(Date.from(clock.instant().plus(properties.tokenTtl())));
        token.addGrants(
                new RoomJoin(true),
                new RoomName(roomId.toString()),
                new CanSubscribe(true),
                new CanPublish(canPublish),
                new CanPublishData(canPublish));
        return new MediaConnection(properties.url(), token.toJwt());
    }

    @Override
    public void updatePublishPermission(UUID roomId, String userId, boolean canPublish) {
        var permission = ParticipantPermission.newBuilder()
                .setCanSubscribe(true)
                .setCanPublish(canPublish)
                .setCanPublishData(canPublish)
                .build();
        // 404 = participant not connected right now; the permission then applies via the token issued on join.
        execute(roomService.updateParticipant(roomId.toString(), userId, null, null, permission),
                "update participant permission");
    }

    @Override
    public void closeRoom(UUID roomId) {
        execute(roomService.deleteRoom(roomId.toString()), "delete room");
    }

    @Override
    public void setSubscriptions(UUID roomId, String userId, List<String> trackSids, boolean subscribe) {
        if (trackSids.isEmpty()) {
            return;
        }
        execute(roomService.updateSubscriptions(roomId.toString(), userId, trackSids, subscribe), "update subscriptions");
    }

    @Override
    public void sendSignal(UUID roomId, String topic, byte[] payload, List<String> destinationIdentities) {
        // Parameter order in the SDK: destination SIDs first, then destination identities.
        execute(roomService.sendData(roomId.toString(), payload, DataPacket.Kind.RELIABLE,
                List.of(), destinationIdentities, topic), "send data message");
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
