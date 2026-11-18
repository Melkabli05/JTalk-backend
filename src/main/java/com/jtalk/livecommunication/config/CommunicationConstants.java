package com.jtalk.livecommunication.config;

/** Limits shared by request validation and the database constraint. */
public final class CommunicationConstants {

    /** Stage seats include the host. */
    public static final int MIN_STAGE_SEATS = 2;
    public static final int MAX_STAGE_SEATS = 12;
    public static final int DEFAULT_STAGE_SEATS = 8;

    /** The most rooms one page of discovery (and the first message of the room feed) carries. */
    public static final int MAX_PAGE_SIZE = 100;

    private CommunicationConstants() {
    }
}
