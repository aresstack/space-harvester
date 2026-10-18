package com.aresstack.enterpriseai.speech.api;

/** Gesprochenes Audio als vollständige WAV-Datei (RIFF, PCM). */
public final class SynthesizedSpeech {

    private final byte[] wav;

    public SynthesizedSpeech(byte[] wav) {
        if (wav == null || wav.length == 0) {
            throw new IllegalArgumentException("wav must not be empty");
        }
        this.wav = wav.clone();
    }

    /** Eine Kopie der WAV-Bytes. */
    public byte[] wav() {
        return wav.clone();
    }

    public int size() {
        return wav.length;
    }

    @Override
    public String toString() {
        return "SynthesizedSpeech[" + wav.length + " bytes]";
    }
}
