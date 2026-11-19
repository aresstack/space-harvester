package com.aresstack.spaceharvester.app.transcription;

import java.net.URI;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Decode a credential-free audio-only media source into 16-kHz mono S16LE PCM.
 * Hand every decoded block to the sink as soon as it exists, so recognition can run alongside the download.
 */
public interface AudioPcmDecoder {

    /**
     * Decode the whole source, as fast as the source delivers it (never paced to playback speed).
     * Return normally once the source ended; throw on failure or cancellation. The sink keeps what arrived before.
     */
    void decode(URI audioSource, BooleanSupplier cancelled, PcmSink sink, LongConsumer onDecodedSeconds)
            throws Exception;
}
