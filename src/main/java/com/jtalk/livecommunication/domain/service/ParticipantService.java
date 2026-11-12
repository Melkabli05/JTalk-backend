package com.jtalk.livecommunication.domain.service;

import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.model.Participant;
import com.jtalk.livecommunication.domain.service.MediaService.MediaConnection;

import java.util.List;
import java.util.UUID;

/** Room membership: joining (with a media token), presence heartbeats, leaving, and who is here. */
public interface ParticipantService {

    /** Result of joining: how to reach the media server and which role the user holds. */
    record Joined(MediaConnection connection, ParticipantRole role) {
    }

    /**
     * Joins the room. Publish rights are decided here: the host and users who already hold a stage seat
     * (e.g. rejoining after a drop) may publish; everyone else is a listener. Joining cancels a running
     * grace lease.
     */
    Joined join(UUID roomId, String userId);

    void heartbeat(UUID roomId, String userId);

    void leave(UUID roomId, String userId);

    boolean isPresent(UUID roomId, String userId);

    List<Participant> listPresent(UUID roomId);
}
