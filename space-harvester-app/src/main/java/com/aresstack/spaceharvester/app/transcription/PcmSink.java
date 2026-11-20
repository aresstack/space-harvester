package com.aresstack.spaceharvester.app.transcription;

import java.io.IOException;

/** Receive consecutive blocks of 16-kHz mono S16LE PCM while decoding is still running. */
@FunctionalInterface
public interface PcmSink {

    /** Append {@code length} bytes from {@code samples}, starting at {@code offset}. */
    void write(byte[] samples, int offset, int length) throws IOException;
}
