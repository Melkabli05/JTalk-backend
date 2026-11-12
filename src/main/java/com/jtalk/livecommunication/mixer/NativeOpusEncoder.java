package com.jtalk.livecommunication.mixer;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Opus encoder calling the native {@code libopus} through the Foreign Function & Memory API (no JNI, no
 * copies beyond one PCM frame). Needs {@code libopus} on the machine and {@code --enable-native-access}.
 * <p>
 * Library lookup order: {@code -Dopus.library=/path}, env {@code OPUS_LIBRARY}, then the usual system names.
 */
public final class NativeOpusEncoder implements FrameEncoder {

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 1;
    private static final int OPUS_APPLICATION_AUDIO = 2049;
    private static final int OPUS_SET_BITRATE_REQUEST = 4002;
    private static final int MAX_PACKET_BYTES = 1275;   // largest possible Opus packet

    private final Arena arena = Arena.ofShared();
    private final MethodHandle encode;
    private final MethodHandle destroy;
    private final MemorySegment encoder;
    private final MemorySegment pcmBuffer;
    private final MemorySegment packetBuffer;

    public NativeOpusEncoder(int bitrate) {
        SymbolLookup opus = findLibrary(arena)
                .orElseThrow(() -> new IllegalStateException("libopus not found; install it or set -Dopus.library / OPUS_LIBRARY"));
        Linker linker = Linker.nativeLinker();
        try {
            MethodHandle create = linker.downcallHandle(symbol(opus, "opus_encoder_create"), FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            this.encode = linker.downcallHandle(symbol(opus, "opus_encode"), FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            // opus_encoder_ctl is variadic: the value argument (index 2) is the first variadic one.
            MethodHandle ctl = linker.downcallHandle(symbol(opus, "opus_encoder_ctl"), FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    Linker.Option.firstVariadicArg(2));
            this.destroy = linker.downcallHandle(symbol(opus, "opus_encoder_destroy"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            MemorySegment error = arena.allocate(ValueLayout.JAVA_INT);
            this.encoder = (MemorySegment) create.invokeExact(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_AUDIO, error);
            int errorCode = error.get(ValueLayout.JAVA_INT, 0);
            if (errorCode != 0 || encoder.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("opus_encoder_create failed with code " + errorCode);
            }
            int result = (int) ctl.invokeExact(encoder, OPUS_SET_BITRATE_REQUEST, bitrate);
            if (result != 0) {
                throw new IllegalStateException("Setting Opus bitrate failed with code " + result);
            }
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        } catch (Throwable t) {
            arena.close();
            throw new IllegalStateException("Could not initialise libopus", t);
        }
        this.pcmBuffer = arena.allocate((long) RoomMixLoop.FRAME_SAMPLES * Short.BYTES, Short.BYTES);
        this.packetBuffer = arena.allocate(MAX_PACKET_BYTES);
    }

    /** True if a libopus can be loaded on this machine. */
    public static boolean isAvailable() {
        try (Arena probe = Arena.ofConfined()) {
            return findLibrary(probe).isPresent();
        }
    }

    @Override
    public int encode(short[] pcm, byte[] packet) {
        MemorySegment.copy(pcm, 0, pcmBuffer, ValueLayout.JAVA_SHORT, 0, RoomMixLoop.FRAME_SAMPLES);
        int length;
        try {
            length = (int) encode.invokeExact(encoder, pcmBuffer, RoomMixLoop.FRAME_SAMPLES, packetBuffer, MAX_PACKET_BYTES);
        } catch (Throwable t) {
            throw new IllegalStateException("opus_encode call failed", t);
        }
        if (length < 0) {
            throw new IllegalStateException("opus_encode failed with code " + length);
        }
        MemorySegment.copy(packetBuffer, ValueLayout.JAVA_BYTE, 0, packet, 0, length);
        return length;
    }

    @Override
    public void close() {
        try {
            destroy.invokeExact(encoder);
        } catch (Throwable t) {
            throw new IllegalStateException("opus_encoder_destroy failed", t);
        } finally {
            arena.close();
        }
    }

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException("libopus has no symbol " + name));
    }

    private static Optional<SymbolLookup> findLibrary(Arena arena) {
        List<String> candidates = new ArrayList<>();
        String configured = System.getProperty("opus.library", System.getenv("OPUS_LIBRARY"));
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        candidates.addAll(List.of("libopus.dylib", "libopus.so.0", "libopus.so", "opus.dll",
                "/opt/homebrew/lib/libopus.dylib", "/usr/local/lib/libopus.dylib"));
        for (String candidate : candidates) {
            try {
                return Optional.of(SymbolLookup.libraryLookup(candidate, arena));
            } catch (IllegalArgumentException notFound) {
                // try the next candidate
            }
        }
        return Optional.empty();
    }
}
