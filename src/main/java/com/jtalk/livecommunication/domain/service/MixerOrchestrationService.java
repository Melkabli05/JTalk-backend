package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.enums.MixerMode;

import java.util.UUID;

/** Asks the mixer workers to start or stop mixing a room. Both calls are idempotent. */
public interface MixerOrchestrationService {

    void start(UUID roomId, MixerMode mode);

    void stop(UUID roomId, MixerMode mode);
}
