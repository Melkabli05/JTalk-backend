package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.enums.MixerAction;
import com.jtalk.livecommunication.domain.enums.MixerMode;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.model.MixerAssignment;
import com.jtalk.livecommunication.domain.repository.MixerStateRepository;
import com.jtalk.livecommunication.domain.service.MixerOrchestrationService;
import com.jtalk.livecommunication.integration.MixerWorkerClient;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class MixerOrchestrationServiceImpl implements MixerOrchestrationService {

    private final MixerStateRepository state;
    private final MixerWorkerClient workers;

    @Override
    public void start(UUID roomId, MixerMode mode) {
        if (!state.markStarted(roomId, mode)) {
            return;
        }
        try {
            workers.publish(new MixerAssignment(roomId, mode, MixerAction.START));
        } catch (RuntimeException e) {
            state.markStopped(roomId, mode);   // let the next call retry
            throw e;
        }
    }

    @Override
    public void stop(UUID roomId, MixerMode mode) {
        if (state.markStopped(roomId, mode)) {
            workers.publish(new MixerAssignment(roomId, mode, MixerAction.STOP));
        }
    }

    @EventListener
    void onRoomClosed(VoiceRoomClosed event) {
        for (MixerMode mode : MixerMode.values()) {
            stop(event.roomId(), mode);
        }
    }
}
