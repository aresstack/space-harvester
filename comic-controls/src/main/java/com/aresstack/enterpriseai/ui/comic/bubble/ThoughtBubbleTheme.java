package com.aresstack.enterpriseai.ui.comic.bubble;

import java.awt.Color;

/**
 * The colours a {@link AnimatedThoughtBubblePanel} paints with, so the same cloud/animation can render as
 * an amber tool-activity bubble or a green assistant-thinking bubble without duplicating any drawing code.
 */
public final class ThoughtBubbleTheme {

    private final Color background;
    private final Color foreground;
    private final Color accent;
    private final Color failureAccent;

    public ThoughtBubbleTheme(Color background, Color foreground, Color accent, Color failureAccent) {
        this.background = require(background, "background");
        this.foreground = require(foreground, "foreground");
        this.accent = require(accent, "accent");
        this.failureAccent = require(failureAccent, "failureAccent");
    }

    /** Amber tool-/agent-activity colours. */
    public static ThoughtBubbleTheme activity(BubblePalette palette) {
        return new ThoughtBubbleTheme(palette.getActivityBackground(), palette.getActivityForeground(),
                palette.getActivityAccent(), palette.getFailureAccent());
    }

    /** Green assistant-thinking colours — the same family as the normal assistant answer. */
    public static ThoughtBubbleTheme assistant(BubblePalette palette) {
        return new ThoughtBubbleTheme(palette.getAssistantBackground(), palette.getAssistantForeground(),
                palette.getAssistantBackground(), palette.getFailureAccent());
    }

    public Color getBackground() {
        return background;
    }

    public Color getForeground() {
        return foreground;
    }

    public Color getAccent() {
        return accent;
    }

    public Color getFailureAccent() {
        return failureAccent;
    }

    private static Color require(Color color, String name) {
        if (color == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return color;
    }
}
