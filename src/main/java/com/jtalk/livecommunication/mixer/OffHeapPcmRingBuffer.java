package com.jtalk.livecommunication.mixer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-producer / single-consumer ring buffer of 16-bit PCM samples in native memory, so audio never
 * touches the Java heap or the garbage collector. The producer is the transport's audio callback thread,
 * the consumer is the room's mix loop. Writes that do not fit are dropped, never blocked.
 */
public final class OffHeapPcmRingBuffer implements AutoCloseable {

    private final Arena arena = Arena.ofShared();
    private final MemorySegment memory;
    private final int capacity;
    private final AtomicLong writeIndex = new AtomicLong();
    private final AtomicLong readIndex = new AtomicLong();

    public OffHeapPcmRingBuffer(int capacitySamples) {
        if (capacitySamples <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacitySamples;
        this.memory = arena.allocate((long) capacitySamples * Short.BYTES, Short.BYTES);
    }

    public int available() {
        return (int) (writeIndex.getAcquire() - readIndex.getAcquire());
    }

    /** Producer only. Returns how many samples were stored (fewer than {@code length} if the buffer is full). */
    public int write(short[] source, int offset, int length) {
        long write = writeIndex.get();
        int count = (int) Math.min(length, capacity - (write - readIndex.getAcquire()));
        int position = (int) (write % capacity);
        int firstPart = Math.min(count, capacity - position);
        MemorySegment.copy(source, offset, memory, ValueLayout.JAVA_SHORT, (long) position * Short.BYTES, firstPart);
        MemorySegment.copy(source, offset + firstPart, memory, ValueLayout.JAVA_SHORT, 0, count - firstPart);
        writeIndex.setRelease(write + count);
        return count;
    }

    /** Consumer only. Returns how many samples were read (fewer than {@code length} if not enough is buffered). */
    public int read(short[] target, int offset, int length) {
        long read = readIndex.get();
        int count = (int) Math.min(length, writeIndex.getAcquire() - read);
        int position = (int) (read % capacity);
        int firstPart = Math.min(count, capacity - position);
        MemorySegment.copy(memory, ValueLayout.JAVA_SHORT, (long) position * Short.BYTES, target, offset, firstPart);
        MemorySegment.copy(memory, ValueLayout.JAVA_SHORT, 0, target, offset + firstPart, count - firstPart);
        readIndex.setRelease(read + count);
        return count;
    }

    @Override
    public void close() {
        arena.close();
    }
}
