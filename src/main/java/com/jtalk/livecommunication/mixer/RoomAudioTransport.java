package com.jtalk.livecommunication.mixer;

import com.jtalk.livecommunication.domain.enums.MixerMode;

import java.util.UUID;

/**
 * The one piece the mixer cannot bring itself: moving audio between a room and the mix loop.
 * <ul>
 *   <li>{@link MixerMode#FALLBACK}: join the LiveKit room as a hidden participant, feed each stage speaker's
 *       decoded audio into the {@link SpeakerAudioInput}, and publish the mix as the {@code mcu_mono_fallback} track.</li>
 *   <li>{@link MixerMode#BROADCAST}: same input, but push the mix to the audience relay (MoQ / LL-HLS).</li>
 * </ul>
 * LiveKit has no Java realtime SDK, so implementations need a WebRTC stack and are supplied separately
 * through {@link java.util.ServiceLoader}.
 */
public interface RoomAudioTransport {

    boolean supports(MixerMode mode);

    AudioSession open(UUID roomId, MixerMode mode, SpeakerAudioInput input);

    /** An open connection to one room; receives the encoded mix. */
    interface AudioSession extends AutoCloseable {

        void publish(byte[] packet, int length);

        @Override
        void close();
    }
}
