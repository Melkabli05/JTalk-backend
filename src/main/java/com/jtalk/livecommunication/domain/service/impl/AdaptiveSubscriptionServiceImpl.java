package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.config.QualityProperties;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.model.QualityReport;
import com.jtalk.livecommunication.domain.repository.QualityRepository;
import com.jtalk.livecommunication.domain.service.AdaptiveSubscriptionService;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class AdaptiveSubscriptionServiceImpl implements AdaptiveSubscriptionService {

    private final VoiceRoomService rooms;
    private final MediaService media;
    private final QualityRepository quality;
    private final QualityProperties thresholds;
    private final Clock clock;

    @Override
    public void onReport(UUID roomId, String userId, QualityReport report) {
        rooms.getActive(roomId);
        boolean degraded = report.lossPercent() > thresholds.degradedLossPercent()
                || report.downstreamKbps() < thresholds.degradedDownstreamKbps();
        boolean healthy = report.lossPercent() < thresholds.recoveryLossPercent()
                && report.downstreamKbps() >= thresholds.degradedDownstreamKbps();

        if (degraded) {
            quality.markDegraded(roomId, userId);
            prune(roomId, userId, report);
        } else if (healthy) {
            long streakStart = quality.markHealthy(roomId, userId, clock.millis());
            if (streakStart >= 0 && clock.millis() - streakStart >= thresholds.recoveryWindow().toMillis()) {
                restore(roomId, userId);
            }
        } else if (quality.isDegraded(roomId, userId)) {
            quality.markDegraded(roomId, userId);   // in-between: the healthy streak starts over
        }
    }

    /** Drops every subscribed track except the primary one. Nothing to drop means no call to LiveKit. */
    private void prune(UUID roomId, String userId, QualityReport report) {
        List<String> toPrune = report.subscribedTrackSids().stream()
                .filter(sid -> !sid.equals(report.primaryTrackSid()))
                .toList();
        if (toPrune.isEmpty()) {
            return;
        }
        quality.addPruned(roomId, userId, toPrune);
        media.setSubscriptions(roomId, userId, toPrune, false);
    }

    private void restore(UUID roomId, String userId) {
        Set<String> pruned = quality.takePruned(roomId, userId);
        quality.remove(roomId, userId);
        if (!pruned.isEmpty()) {
            media.setSubscriptions(roomId, userId, List.copyOf(pruned), true);
        }
    }

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        quality.clear(event.roomId());
    }
}
