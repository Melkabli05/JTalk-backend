package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.config.QualityProperties;
import com.jtalk.livecommunication.domain.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.domain.enums.NetworkMode;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.model.QualityReport;
import com.jtalk.livecommunication.domain.repository.QualityRepository;
import com.jtalk.livecommunication.domain.service.AdaptiveSubscriptionService;
import com.jtalk.livecommunication.domain.service.StageService;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class AdaptiveSubscriptionServiceImpl implements AdaptiveSubscriptionService {

    private final StageService stage;
    private final QualityRepository quality;
    private final QualityProperties thresholds;
    private final Clock clock;

    @Override
    public void onReport(UUID roomId, UUID userId, QualityReport report) {
        boolean degraded = report.lossPercent() > thresholds.degradedLossPercent()
                || report.downstreamKbps() < thresholds.degradedDownstreamKbps();
        boolean healthy = report.lossPercent() < thresholds.recoveryLossPercent()
                && report.downstreamKbps() >= thresholds.degradedDownstreamKbps();

        if (degraded) {
            quality.markDegraded(roomId, userId);
            List<String> toPrune = report.subscribedTrackSids().stream().filter(sid -> !sid.equals(report.primaryTrackSid())).toList();
            stage.updateNetworkMode(roomId, userId, new NetworkModeRequest(NetworkMode.LOW_BANDWIDTH, toPrune, List.of()));
        } else if (healthy) {
            long streakStart = quality.markHealthy(roomId, userId, clock.millis());
            if (streakStart >= 0 && clock.millis() - streakStart >= thresholds.recoveryWindow().toMillis()) {
                quality.remove(roomId, userId);
                stage.updateNetworkMode(roomId, userId, new NetworkModeRequest(NetworkMode.OPTIMAL, List.of(), List.of()));
            }
        } else if (quality.isDegraded(roomId, userId)) {
            quality.markDegraded(roomId, userId);   // in-between: the healthy streak starts over
        }
    }

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        quality.clear(event.roomId());
    }
}
