package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.repository.LeaseRepository;
import com.jtalk.livecommunication.domain.service.StageLeaseService;
import com.jtalk.livecommunication.domain.service.StageService;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Set;
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
        stage.releaseSeatAndPromoteNext(roomId, userId);
    }

    @Override
    public Set<String> reconnecting(UUID roomId, List<String> userIds) {
        return leases.usersWithLease(roomId, userIds);
    }
}
