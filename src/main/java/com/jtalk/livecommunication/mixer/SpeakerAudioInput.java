package com.jtalk.livecommunication.mixer;

/** Where a transport delivers incoming speaker audio: one ring buffer per speaker, 48 kHz mono 16-bit PCM. */
public interface SpeakerAudioInput {

    /** The buffer for this speaker; created on first use. Write 20 ms frames into it. */
    OffHeapPcmRingBuffer bufferFor(String speakerId);

    void removeSpeaker(String speakerId);
}
