package com.aresstack.enterpriseai.ui.comic.bubble;

/**
 * THE bubble timestamp format — one origin for every mode. Partying's group bubbles introduced the
 * stacked time-over-date block with the long-form tooltip; Questing's chat and tool-activity bubbles
 * reuse exactly these strings, so a message stamp reads identically wherever it appears.
 */
public final class BubbleTimestamps {

    private BubbleTimestamps() {
    }

    /** {@code "14:03"} — the bold upper line of the stacked block. */
    public static String timeText(long epochMillis) {
        return new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(epochMillis));
    }

    /** {@code "24/08/26"} — the lower line of the stacked block. */
    public static String dateText(long epochMillis) {
        return new java.text.SimpleDateFormat("dd/MM/yy").format(new java.util.Date(epochMillis));
    }

    /** {@code "Mo 24/08/2026 14:03"} — the shared hover tooltip. */
    public static String tooltip(long epochMillis) {
        return new java.text.SimpleDateFormat("EEE dd/MM/yyyy HH:mm")
                .format(new java.util.Date(epochMillis));
    }

    /** The two half-size stacked lines (bold time over date), right-aligned — as introduced in Partying. */
    public static String stackedHtml(long epochMillis) {
        return "<html><div style='line-height:90%;text-align:right'><b>"
                + timeText(epochMillis) + "</b><br>" + dateText(epochMillis) + "</div></html>";
    }
}
