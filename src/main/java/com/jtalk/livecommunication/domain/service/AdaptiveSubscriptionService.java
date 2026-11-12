package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.model.QualityReport;

import java.util.UUID;

/** Switches struggling listeners between the individual speaker tracks and the single mixed fallback track. */
public interface AdaptiveSubscriptionService {

    void onReport(UUID roomId, String userId, QualityReport report);
}
