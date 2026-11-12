package com.jtalk.livecommunication.domain.model;

/** Downstream quality as measured by a client. */
public record QualityReport(double lossPercent, int downstreamKbps) {
}
