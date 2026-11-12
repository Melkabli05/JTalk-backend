package com.jtalk.livecommunication.domain.model;

import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * Downstream quality as measured by a client, plus what it is currently subscribed to and which of those tracks
 * it wants to keep when its connection is struggling (usually the active speaker).
 */
public record QualityReport(double lossPercent, int downstreamKbps, List<String> subscribedTrackSids, @Nullable String primaryTrackSid) {

    public QualityReport {
        subscribedTrackSids = subscribedTrackSids == null ? List.of() : List.copyOf(subscribedTrackSids);
    }
}
