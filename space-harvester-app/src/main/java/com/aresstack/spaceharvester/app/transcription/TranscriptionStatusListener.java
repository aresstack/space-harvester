package com.aresstack.spaceharvester.app.transcription;

/** Report actual audio-decoding progress and the transition to speech recognition. */
public interface TranscriptionStatusListener {

    void onDecodedSeconds(long seconds);

    void onRecognitionStarted();
}
