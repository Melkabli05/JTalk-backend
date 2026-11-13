package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.model.QualityReport;
import io.micronaut.core.annotation.NonNull;

import java.util.UUID;

/**
 * Decides when a client is struggling from its quality reports and switches it into (and out of) low-bandwidth mode, so
 * clients do not need their own thresholds.
 */
public interface AdaptiveSubscriptionService {

    void onReport(@NonNull UUID roomId, @NonNull UUID userId, @NonNull QualityReport report);
}
