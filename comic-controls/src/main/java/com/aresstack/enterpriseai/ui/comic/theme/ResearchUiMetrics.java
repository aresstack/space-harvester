package com.aresstack.enterpriseai.ui.comic.theme;

/**
 * The pixel metrics of the dark research-UI design study — radii, control heights and paddings.
 * Like {@link ResearchUiPalette}: controls read from here, never own local copies.
 */
public final class ResearchUiMetrics {

    /** Standard corner radius of normal controls (pills, footer buttons). */
    public static final int RADIUS_CONTROL = 10;
    /** Corner radius of secondary controls (the "+ Hinzufügen" chip). */
    public static final int RADIUS_SECONDARY = 12;
    /** Corner radius of the blacklist chips. */
    public static final int RADIUS_CHIP = 18;
    /**
     * Corner radius of a frameless window: the 8px that Windows 11 gives the decorated askai window
     * (its frame comes from the operating system), so the frameless client keeps the same round corners.
     */
    public static final int RADIUS_WINDOW = 8;

    /** "+ Neuer Chat" button. */
    public static final int NEW_CHAT_HEIGHT = 36;
    public static final int NEW_CHAT_PADDING_H = 14;

    /** Chats footer strip. */
    public static final int FOOTER_HEIGHT = 52;
    public static final int FOOTER_PADDING_H = 12;
    public static final int FOOTER_PADDING_V = 8;
    public static final int FOOTER_CONTROL_HEIGHT = 34;
    public static final int FOOTER_ICON_BUTTON = 34;
    public static final int FOOTER_PILL_PADDING_H = 12;

    /** Phase selector pill. */
    public static final int PHASE_PILL_HEIGHT = 34;
    public static final int PHASE_PILL_MIN_WIDTH = 240;
    public static final int PHASE_PILL_REFERENCE_WIDTH = 266;
    public static final int PHASE_PILL_PADDING_LEFT = 16;
    public static final int PHASE_PILL_PADDING_RIGHT = 14;
    public static final int PHASE_PILL_CHEVRON_GAP = 12;

    /**
     * Top air above SLIM BARS — the drawer's search bar and the collapsed sky status bar share
     * this one value, so their top edges (and with the shared height, their bottom edges) stay
     * aligned automatically.
     */
    public static final int SLIM_BAR_TOP_GAP = 4;

    /** Out-of-scope sky over the transcript's top (Phase 1). */
    public static final int SKY_PADDING_H = 16;
    public static final int SKY_PADDING_TOP = 10;
    /** The soft fade-out below the content zone — the "no hard edge" part of the sky. */
    public static final int SKY_FADE_TAIL = 70;
    /** Collapsed, the sky grows naturally up to this many cloud rows before "+N weitere". */
    public static final int SKY_COLLAPSED_MAX_ROWS = 3;
    /** Expanded, the sky's content may take at most this share of the transcript height. */
    public static final int SKY_EXPANDED_MAX_PERCENT = 33;

    /** Cloud chips (out-of-scope entries): compact, soft, light. */
    public static final int CLOUD_CHIP_HEIGHT = 32;
    public static final int CLOUD_CHIP_PADDING_H = 13;
    public static final int CLOUD_GAP_H = 10;
    public static final int CLOUD_GAP_V = 8;
    public static final int CLOUD_CLOSE_HIT = 18;

    /** Chat-history rows in the drawer (light chrome, two lines). */
    public static final int CHAT_ROW_HEIGHT = 48;
    public static final int CHAT_ROW_RADIUS = 10;
    public static final int CHAT_ROW_PADDING_H = 12;
    /** The 3px vertical accent bar marking the selected row. */
    public static final int CHAT_ROW_ACCENT_WIDTH = 3;

    private ResearchUiMetrics() {
    }
}
