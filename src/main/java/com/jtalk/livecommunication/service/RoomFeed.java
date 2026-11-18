package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.dto.response.RoomFeedEvent;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import org.reactivestreams.Publisher;

import java.util.UUID;

/**
 * What happens to the list of open rooms, pushed to every hallway that is watching, so a client loads the list once and then only
 * applies changes. It travels over Redis pub/sub, so a hallway hears about rooms handled by any instance.
 */
public interface RoomFeed {

    /**
     * Subscribing starts the flow of what happens from then on. {@code READY} comes first, once the instance is listening for everything,
     * so a list fetched after it misses nothing. {@code READY} also comes again if the connection to Redis was lost and came back: what
     * happened in between is gone, so the client loads the list again. A subscriber that falls too far behind is cut off and must reconnect.
     */
    Publisher<RoomFeedEvent> subscribe();

    /** Whether a hallway is watching on any instance; callers skip work that only the feed needs when none is. */
    boolean anyoneWatching();

    void opened(VoiceRoomResponse room);

    void updated(VoiceRoomResponse room);

    void closed(UUID roomId);

    void stats(UUID roomId, RoomLiveStats live);
}
