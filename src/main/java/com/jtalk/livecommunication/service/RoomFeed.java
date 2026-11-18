package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import org.reactivestreams.Publisher;

import java.util.UUID;

/**
 * What happens to the list of open rooms, pushed to every hallway that is watching, so a client loads the list once and then only
 * applies changes. Held in memory: it reaches the clients connected to this instance.
 */
public interface RoomFeed {

    /**
     * Subscribing starts the flow of what happens from then on. {@code READY} comes first, after the subscription is in place, so a
     * list fetched once it arrives misses nothing. A subscriber that falls too far behind is cut off and must reconnect.
     */
    Publisher<RoomFeedEvent> subscribe();

    /** Whether anyone is watching; callers skip work that only the feed needs when nobody is. */
    boolean hasWatchers();

    void opened(VoiceRoomResponse room);

    void updated(VoiceRoomResponse room);

    void closed(UUID roomId);

    void stats(UUID roomId, RoomLiveStats live);
}
