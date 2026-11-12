package com.jtalk.livecommunication.domain.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 30-second grace lease for speakers who drop off the media server: the seat is held while they
 * reconnect, and only reclaimed (and the next hand-raiser promoted) if the lease expires.
 */
public interface StageLeaseService {

    /** Starts a lease if the user holds a stage seat. Returns false if they do not. */
    boolean onDisconnect(UUID roomId, String userId);

    /** The user is back: cancels any running lease. */
    void onReconnect(UUID roomId, String userId);

    /** The lease expired: frees the seat and promotes the next hand-raiser. */
    void reclaim(UUID roomId, String userId);

    /** Which of these users are currently inside a grace lease (one Redis round trip for all of them). */
    Set<String> reconnecting(UUID roomId, List<String> userIds);
}
