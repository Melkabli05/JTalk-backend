package com.jtalk.livecommunication.mixer;

/** Encodes one {@link RoomMixLoop#FRAME_SAMPLES}-sample mono PCM frame into a compressed packet. */
public interface FrameEncoder extends AutoCloseable {

    /** @return number of bytes written into {@code packet} */
    int encode(short[] pcm, byte[] packet);

    @Override
    void close();
}
