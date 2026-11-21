package com.aresstack.spaceharvester.app.transcription;

/**
 * Represent one committed Whisper window of at most thirty seconds, from its first to the end of its last segment.
 * The end is where the next window starts; a transcription resumes there after it was stopped.
 */
public final class TranscriptSegment {

    private final long startMillis;
    private final long endMillis;
    private final String text;

    /** A window whose end is not known (no timestamps): it ends where it starts. */
    public TranscriptSegment(long startMillis, String text) {
        this(startMillis, startMillis, text);
    }

    public TranscriptSegment(long startMillis, long endMillis, String text) {
        if (startMillis < 0 || endMillis < startMillis || text == null) {
            throw new IllegalArgumentException("Invalid transcription segment");
        }
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.text = text;
    }

    public long startMillis() {
        return startMillis;
    }

    public long endMillis() {
        return endMillis;
    }

    public String text() {
        return text;
    }

    /** The same window {@code millis} later, for a transcription that started there. */
    public TranscriptSegment shifted(long millis) {
        return new TranscriptSegment(startMillis + millis, endMillis + millis, text);
    }

    /** {@code 00:01:13}, the start of the window. */
    public String timeLabel() {
        return clock(startMillis);
    }

    /** {@code 00:01:13 – 00:01:40}, start and end; without a known end only the start. */
    public String rangeLabel() {
        return endMillis > startMillis ? clock(startMillis) + RANGE_SEPARATOR + clock(endMillis) : clock(startMillis);
    }

    /** Between start and end in {@link #rangeLabel}. */
    public static final String RANGE_SEPARATOR = " – ";

    /** {@code hh:mm:ss}. */
    public static String clock(long millis) {
        long seconds = millis / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
