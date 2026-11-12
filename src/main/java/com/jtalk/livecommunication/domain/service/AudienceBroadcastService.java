package com.jtalk.livecommunication.domain.service;

import java.util.UUID;

/** Starts the audience-tier broadcast mix when a room grows large, and stops it when it shrinks again. */
public interface AudienceBroadcastService {

    void recheck(UUID roomId, long audienceSize);
}
