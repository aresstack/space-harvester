package com.aresstack.enterpriseai.ui.comic.bubble;

import java.awt.Color;

/** Keep all bubble colors in one replaceable value object. */
public final class BubblePalette {

    private final Color transcriptBackground;
    private final Color userBackground;
    private final Color userForeground;
    private final Color assistantBackground;
    private final Color assistantForeground;
    private final Color activityBackground;
    private final Color activityForeground;
    private final Color activityAccent;
    private final Color failureAccent;
    private final Color infoForeground;

    public BubblePalette(Color transcriptBackground,
                         Color userBackground,
                         Color userForeground,
                         Color assistantBackground,
                         Color assistantForeground,
                         Color activityBackground,
                         Color activityForeground,
                         Color activityAccent,
                         Color failureAccent,
                         Color infoForeground) {
        this.transcriptBackground = requireColor(transcriptBackground, "transcriptBackground");
        this.userBackground = requireColor(userBackground, "userBackground");
        this.userForeground = requireColor(userForeground, "userForeground");
        this.assistantBackground = requireColor(assistantBackground, "assistantBackground");
        this.assistantForeground = requireColor(assistantForeground, "assistantForeground");
        this.activityBackground = requireColor(activityBackground, "activityBackground");
        this.activityForeground = requireColor(activityForeground, "activityForeground");
        this.activityAccent = requireColor(activityAccent, "activityAccent");
        this.failureAccent = requireColor(failureAccent, "failureAccent");
        this.infoForeground = requireColor(infoForeground, "infoForeground");
    }

    public static BubblePalette windowsPhoneInspired() {
        return new BubblePalette(
                new Color(0xF4F4F4),
                new Color(0x1676D2),
                Color.WHITE,
                new Color(0x15827A),
                Color.WHITE,
                new Color(0xF2C94C),
                new Color(0x252525),
                new Color(0xE39A18),
                new Color(0xC94C4C),
                new Color(0x6B6F76));
    }

    /**
     * Dieselbe Palette mit anderen Farben für Nutzer- und Assistentenblasen (die Einstellungen
     * {@code chat.bubble.*}); Verlauf, Wolken, Fehler und Hinweise behalten ihre Farben.
     */
    public BubblePalette withConversationColors(Color userBackground, Color userForeground,
                                                Color assistantBackground, Color assistantForeground) {
        return new BubblePalette(transcriptBackground, userBackground, userForeground, assistantBackground,
                assistantForeground, activityBackground, activityForeground, activityAccent, failureAccent,
                infoForeground);
    }

    public Color getTranscriptBackground() {
        return transcriptBackground;
    }

    public Color getUserBackground() {
        return userBackground;
    }

    public Color getUserForeground() {
        return userForeground;
    }

    public Color getAssistantBackground() {
        return assistantBackground;
    }

    public Color getAssistantForeground() {
        return assistantForeground;
    }

    public Color getActivityBackground() {
        return activityBackground;
    }

    public Color getActivityForeground() {
        return activityForeground;
    }

    public Color getActivityAccent() {
        return activityAccent;
    }

    public Color getFailureAccent() {
        return failureAccent;
    }

    public Color getInfoForeground() {
        return infoForeground;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BubblePalette)) {
            return false;
        }
        BubblePalette that = (BubblePalette) other;
        return transcriptBackground.equals(that.transcriptBackground) && userBackground.equals(that.userBackground)
                && userForeground.equals(that.userForeground) && assistantBackground.equals(that.assistantBackground)
                && assistantForeground.equals(that.assistantForeground)
                && activityBackground.equals(that.activityBackground)
                && activityForeground.equals(that.activityForeground) && activityAccent.equals(that.activityAccent)
                && failureAccent.equals(that.failureAccent) && infoForeground.equals(that.infoForeground);
    }

    @Override
    public int hashCode() {
        int result = transcriptBackground.hashCode();
        result = 31 * result + userBackground.hashCode();
        result = 31 * result + userForeground.hashCode();
        result = 31 * result + assistantBackground.hashCode();
        result = 31 * result + assistantForeground.hashCode();
        result = 31 * result + activityBackground.hashCode();
        result = 31 * result + activityForeground.hashCode();
        result = 31 * result + activityAccent.hashCode();
        result = 31 * result + failureAccent.hashCode();
        return 31 * result + infoForeground.hashCode();
    }

    private static Color requireColor(Color color, String name) {
        if (color == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return color;
    }
}
