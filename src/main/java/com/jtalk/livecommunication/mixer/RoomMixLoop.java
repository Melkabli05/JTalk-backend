package com.jtalk.livecommunication.mixer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Mixes all speakers of one room every 20 ms: reads one frame from each speaker's ring buffer, sums them
 * in an {@code int} accumulator, soft-clips, encodes and hands the packet to the sink. Silent ticks (no
 * speaker had audio) send nothing.
 */
public final class RoomMixLoop implements SpeakerAudioInput, AutoCloseable {

    /** 20 ms at 48 kHz, mono. */
    public static final int FRAME_SAMPLES = 960;
    static final long FRAME_NANOS = 20_000_000L;
    private static final int BUFFER_FRAMES = 25;   // 500 ms of jitter tolerance per speaker
    private static final int MAX_PACKET_BYTES = 1275;

    /** Receives each encoded packet. */
    @FunctionalInterface
    public interface PacketSink {
        void accept(byte[] packet, int length);
    }

    private final ConcurrentMap<String, OffHeapPcmRingBuffer> inputs = new ConcurrentHashMap<>();
    private final FrameEncoder encoder;
    private final int[] accumulator = new int[FRAME_SAMPLES];
    private final short[] speakerFrame = new short[FRAME_SAMPLES];
    private final short[] mixedFrame = new short[FRAME_SAMPLES];
    private final byte[] packet = new byte[MAX_PACKET_BYTES];
    private volatile boolean running;
    private Thread thread;

    public RoomMixLoop(FrameEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public OffHeapPcmRingBuffer bufferFor(String speakerId) {
        return inputs.computeIfAbsent(speakerId, id -> new OffHeapPcmRingBuffer(FRAME_SAMPLES * BUFFER_FRAMES));
    }

    @Override
    public void removeSpeaker(String speakerId) {
        OffHeapPcmRingBuffer removed = inputs.remove(speakerId);
        if (removed != null) {
            removed.close();
        }
    }

    /** One mixing step. Returns false if nobody had audio (nothing was sent). */
    public boolean tick(PacketSink sink) {
        java.util.Arrays.fill(accumulator, 0);
        boolean anyAudio = false;
        for (OffHeapPcmRingBuffer input : inputs.values()) {
            int samples = input.read(speakerFrame, 0, FRAME_SAMPLES);
            for (int i = 0; i < samples; i++) {
                accumulator[i] += speakerFrame[i];
            }
            anyAudio |= samples > 0;
        }
        if (!anyAudio) {
            return false;
        }
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            mixedFrame[i] = SoftKneeClipper.clip(accumulator[i]);
        }
        sink.accept(packet, encoder.encode(mixedFrame, packet));
        return true;
    }

    /** Starts the 20 ms real-time loop on its own thread. */
    public void start(PacketSink sink) {
        running = true;
        thread = Thread.ofPlatform().name("room-mix-loop").daemon(true).start(() -> {
            long next = System.nanoTime();
            while (running) {
                tick(sink);
                next += FRAME_NANOS;
                LockSupport.parkNanos(next - System.nanoTime());
            }
        });
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        inputs.values().forEach(OffHeapPcmRingBuffer::close);
        inputs.clear();
        encoder.close();
    }
}
