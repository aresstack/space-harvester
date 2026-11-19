package com.aresstack.spaceharvester.app.transcription;

/** Represent one committed Whisper window of at most thirty seconds, starting at its first segment. */
public final class TranscriptSegment {

    private final long startMillis;
    private final String text;

    public TranscriptSegment(long startMillis, String text) {
        if (startMillis < 0 || text == null) {
            throw new IllegalArgumentException("Invalid transcription segment");
        }
        this.startMillis = startMillis;
        this.text = text;
    }

    public long startMillis() {
        return startMillis;
    }

    public String text() {
        return text;
    }

    public String timeLabel() {
        long seconds = startMillis / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
