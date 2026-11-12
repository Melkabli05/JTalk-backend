package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.config.MixerProperties;
import com.jtalk.livecommunication.domain.enums.MixerMode;
import com.jtalk.livecommunication.domain.service.AudienceBroadcastService;
import com.jtalk.livecommunication.domain.service.MixerOrchestrationService;
import jakarta.inject.Singleton;

import java.util.UUID;

@Singleton
class AudienceBroadcastServiceImpl implements AudienceBroadcastService {

    /** Stop only once the audience is clearly below the threshold, so the mix does not flap. */
    private static final double STOP_RATIO = 0.8;

    private final MixerOrchestrationService mixers;
    private final int threshold;

    AudienceBroadcastServiceImpl(MixerOrchestrationService mixers, MixerProperties properties) {
        this.mixers = mixers;
        this.threshold = properties.audienceThreshold();
    }

    @Override
    public void recheck(UUID roomId, long audienceSize) {
        if (audienceSize >= threshold) {
            mixers.start(roomId, MixerMode.BROADCAST);
        } else if (audienceSize < threshold * STOP_RATIO) {
            mixers.stop(roomId, MixerMode.BROADCAST);
        }
    }
}
