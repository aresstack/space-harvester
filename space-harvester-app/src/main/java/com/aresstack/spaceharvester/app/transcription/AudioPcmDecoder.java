package com.aresstack.spaceharvester.app.transcription;

import java.net.URI;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Decode a credential-free audio-only media source into temporary 16-kHz mono S16LE PCM. */
public interface AudioPcmDecoder {
    Path decode(URI audioSource, BooleanSupplier cancelled) throws Exception;

    default Path decode(URI audioSource, BooleanSupplier cancelled,
                        LongConsumer onDecodedSeconds) throws Exception {
        return decode(audioSource, cancelled);
    }
}
