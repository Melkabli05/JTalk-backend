package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.config.MixerProperties;
import com.jtalk.livecommunication.config.QualityProperties;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.MixerMode;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.model.QualityReport;
import com.jtalk.livecommunication.domain.repository.QualityRepository;
import com.jtalk.livecommunication.domain.service.AdaptiveSubscriptionService;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.domain.service.MediaService.MediaTrack;
import com.jtalk.livecommunication.domain.service.MixerOrchestrationService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Singleton
class AdaptiveSubscriptionServiceImpl implements AdaptiveSubscriptionService {

    private final VoiceRoomService rooms;
    private final StageService stage;
    private final MediaService media;
    private final MixerOrchestrationService mixers;
    private final QualityRepository quality;
    private final QualityProperties thresholds;
    private final String fallbackTrackName;
    private final Clock clock;

    AdaptiveSubscriptionServiceImpl(VoiceRoomService rooms,
                                    StageService stage,
                                    MediaService media,
                                    MixerOrchestrationService mixers,
                                    QualityRepository quality,
                                    QualityProperties thresholds,
                                    MixerProperties mixerProperties,
                                    Clock clock) {
        this.rooms = rooms;
        this.stage = stage;
        this.media = media;
        this.mixers = mixers;
        this.quality = quality;
        this.thresholds = thresholds;
        this.fallbackTrackName = mixerProperties.fallbackTrackName();
        this.clock = clock;
    }

    @Override
    public void onReport(UUID roomId, String userId, QualityReport report) {
        VoiceRoom room = rooms.getActive(roomId);
        boolean degraded = report.lossPercent() > thresholds.degradedLossPercent()
                || report.downstreamKbps() < thresholds.degradedDownstreamKbps();
        boolean healthy = report.lossPercent() < thresholds.recoveryLossPercent()
                && report.downstreamKbps() >= thresholds.degradedDownstreamKbps();

        if (degraded) {
            quality.markDegraded(roomId, userId);
            mixers.start(roomId, MixerMode.FALLBACK);
            switchTo(room, userId, true);
        } else if (healthy) {
            long streakStart = quality.markHealthy(roomId, userId, clock.millis());
            if (streakStart >= 0 && clock.millis() - streakStart >= thresholds.recoveryWindow().toMillis()) {
                switchTo(room, userId, false);
                if (quality.remove(roomId, userId) == 0) {
                    mixers.stop(roomId, MixerMode.FALLBACK);
                }
            }
        } else if (quality.isDegraded(roomId, userId)) {
            quality.markDegraded(roomId, userId);   // in-between: the healthy streak starts over
        }
    }

    /**
     * Moves the listener between the individual speaker tracks and the mixed fallback track. While the
     * fallback track is not published yet the listener stays on the speaker tracks, so they never go silent;
     * the next degraded report retries.
     */
    private void switchTo(VoiceRoom room, String userId, boolean fallback) {
        List<MediaTrack> tracks = media.listTracks(room.id());
        Optional<MediaTrack> mixed = tracks.stream().filter(t -> t.name().equals(fallbackTrackName)).findFirst();
        if (mixed.isEmpty()) {
            return;
        }
        Set<String> speakers = new HashSet<>(stage.getStage(room.id()).speakers());
        speakers.add(room.hostId());
        List<String> speakerSids = tracks.stream()
                .filter(t -> speakers.contains(t.identity()) && !t.identity().equals(userId) && !t.name().equals(fallbackTrackName))
                .map(MediaTrack::sid)
                .toList();

        media.setSubscriptions(room.id(), userId, speakerSids, !fallback);
        media.setSubscriptions(room.id(), userId, List.of(mixed.get().sid()), fallback);
    }

    @EventListener
    void onRoomClosed(VoiceRoomClosed event) {
        quality.clear(event.roomId());
    }
}
