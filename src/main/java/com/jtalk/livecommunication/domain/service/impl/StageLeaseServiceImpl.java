package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.repository.LeaseRepository;
import com.jtalk.livecommunication.domain.service.StageLeaseService;
import com.jtalk.livecommunication.domain.service.StageService;
import jakarta.inject.Singleton;

import java.util.UUID;

@Singleton
class StageLeaseServiceImpl implements StageLeaseService {

    private final LeaseRepository leases;
    private final StageService stage;

    StageLeaseServiceImpl(LeaseRepository leases, StageService stage) {
        this.leases = leases;
        this.stage = stage;
    }

    @Override
    public boolean onDisconnect(UUID roomId, String userId) {
        if (!stage.isOnStage(roomId, userId)) {
            return false;
        }
        leases.create(roomId, userId);
        return true;
    }

    @Override
    public void onReconnect(UUID roomId, String userId) {
        leases.delete(roomId, userId);
    }

    @Override
    public void reclaim(UUID roomId, String userId) {
        stage.releaseSeat(roomId, userId);
        stage.promoteNext(roomId);
    }

    @Override
    public boolean isReconnecting(UUID roomId, String userId) {
        return leases.exists(roomId, userId);
    }
}
