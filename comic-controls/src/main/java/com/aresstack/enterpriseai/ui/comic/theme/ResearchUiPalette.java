package com.aresstack.enterpriseai.ui.comic.theme;

import java.awt.Color;

/**
 * The ONE color table of the dark research-UI design study (phase selector, chats footer, blacklist
 * chips, …). New Java2D controls read their colors from here instead of hardcoding hex values per
 * class — the study's palette must stay changeable in exactly one place.
 */
public final class ResearchUiPalette {

    /** Deepest window background of the study. */
    public static final Color DARK_BASE = ThemeColor.of("research.DARK_BASE");
    /** Raised dark surface (cards, popups). */
    public static final Color DARK_SURFACE = ThemeColor.of("research.DARK_SURFACE");
    /** The workspace strip under the composer (blacklist area). */
    public static final Color WORKSPACE_SURFACE = ThemeColor.of("research.WORKSPACE_SURFACE");
    /** Chip body fill. */
    public static final Color CHIP_SURFACE = ThemeColor.of("research.CHIP_SURFACE");
    /** Secondary dark controls (buttons, language pill, add chip). */
    public static final Color SECONDARY_SURFACE = ThemeColor.of("research.SECONDARY_SURFACE");

    public static final Color TEXT_PRIMARY = ThemeColor.of("research.TEXT_PRIMARY");
    public static final Color TEXT_MUTED = ThemeColor.of("research.TEXT_MUTED");
    public static final Color TEXT_DARK = ThemeColor.of("research.TEXT_DARK");

    public static final Color BORDER_DARK = ThemeColor.of("research.BORDER_DARK");
    public static final Color BORDER_WINDOW = ThemeColor.of("research.BORDER_WINDOW");

    /**
     * THE AskAI accent blue of the active navigation (the highlighted Chats ribbon entry / composer
     * primary — {@code ChatComposerPanel} reads its PRIMARY from here). The phase selector, the
     * chat-list selection and the pressed state of {@code ResearchPillButton} ("+ Neuer Chat") use
     * exactly this token; no second blue palette and no purple accent exist.
     */
    public static final Color ACCENT_BLUE = ThemeColor.of("research.ACCENT_BLUE");
    /** The destructive red of the composer family (delete-all hover, stop button). */
    public static final Color DANGER_RED = ThemeColor.of("research.DANGER_RED");
    /** Dictation (arch {@code ChatComposerPanel}): orange border and stop fill while recording, warm surface. */
    public static final Color RECORDING_ORANGE = ThemeColor.of("research.RECORDING_ORANGE");
    public static final Color RECORDING_SURFACE = ThemeColor.of("research.RECORDING_SURFACE");
    /** Accent of the active find in the reader: MainframeMate's amber focus border of its find bar (amber-700). */
    public static final Color FIND_AMBER = ThemeColor.of("research.FIND_AMBER");
    /** The comic dark green of the recording waveform over the composer editor (arch). */
    public static final Color WAVE_GREEN = ThemeColor.of("research.WAVE_GREEN");
    /** The favorite star (MainframeMate's bookmarked ★ in the tab header) and its darker rim on bright surfaces. */
    public static final Color FAVORITE_YELLOW = ThemeColor.of("research.FAVORITE_YELLOW");
    public static final Color FAVORITE_YELLOW_EDGE = ThemeColor.of("research.FAVORITE_YELLOW_EDGE");
    /** The red compass of the navigation tab (issue #97, Angelo's design: red ring and needle). */
    public static final Color COMPASS_RED = ThemeColor.of("research.COMPASS_RED");
    /**
     * The pale pink behind acronyms in the reader (issue #107): translucent, so the yellow and amber find marks and
     * the selection stay visible underneath and the text stays easy to read.
     */
    public static final Color ACRONYM_MARK = ThemeColor.of("research.ACRONYM_MARK");
    /**
     * The yellow suggestion tag above the composer (the research agent's search suggestions and the follow-up
     * questions of a quote): the same yellow as the tool-call thought bubbles, with its dark text.
     */
    public static final Color TAG_SUGGESTION_SURFACE = ThemeColor.of("research.TAG_SUGGESTION_SURFACE");
    public static final Color TAG_SUGGESTION_TEXT = ThemeColor.of("research.TAG_SUGGESTION_TEXT");
    /** The paper of the "Chats" scroll glyph in the drawer ribbon. */
    public static final Color SCROLL_PAPER = ThemeColor.of("research.SCROLL_PAPER");

    /** Light-chrome tokens for design-study controls embedded in the BRIGHT AskAI surfaces. */
    public static final Color LIGHT_CONTROL_BG = ThemeColor.of("research.LIGHT_CONTROL_BG");
    public static final Color LIGHT_CONTROL_BORDER = ThemeColor.of("research.LIGHT_CONTROL_BORDER");
    public static final Color LIGHT_CONTROL_TEXT = ThemeColor.of("research.LIGHT_CONTROL_TEXT");
    public static final Color LIGHT_TEXT_MUTED = ThemeColor.of("research.LIGHT_TEXT_MUTED");

    /** Hover fill of secondary dark controls. */
    public static final Color SECONDARY_HOVER = ThemeColor.of("research.SECONDARY_HOVER");
    /** Hovered chip body / hovered chip border. */
    public static final Color CHIP_HOVER_SURFACE = ThemeColor.of("research.CHIP_HOVER_SURFACE");
    public static final Color CHIP_HOVER_BORDER = ThemeColor.of("research.CHIP_HOVER_BORDER");
    /** Round hover backdrop behind a chip's ✕. */
    public static final Color CHIP_CLOSE_HOVER = ThemeColor.of("research.CHIP_CLOSE_HOVER");
    /** The subtle divider on top of dark workspace strips. */
    public static final Color WORKSPACE_DIVIDER = ThemeColor.of("research.WORKSPACE_DIVIDER");

    // ------------------------------------------------------------------ out-of-scope sky
    // Deliberately air-like, harmonizing with the bright AskAI surfaces — never a dominant blue.

    /** The sky's top tone; it fades to fully transparent towards the chat. */
    public static final Color SKY_TOP = ThemeColor.of("research.SKY_TOP");
    /**
     * The COLLAPSED sky status bar: a firmer, calm sky blue so the bar reads as a deliberate
     * control — clearly stronger than the airy near-white of the open sky.
     */
    public static final Color SKY_BAR_SURFACE = ThemeColor.of("research.SKY_BAR_SURFACE");
    /** The quiet caption ("Außerhalb des Scopes"). */
    public static final Color SKY_CAPTION = ThemeColor.of("research.SKY_CAPTION");
    /** Cloud chips: light body, soft blue border, calm slate text. */
    public static final Color CLOUD_SURFACE = ThemeColor.of("research.CLOUD_SURFACE");
    public static final Color CLOUD_BORDER = ThemeColor.of("research.CLOUD_BORDER");
    public static final Color CLOUD_TEXT = ThemeColor.of("research.CLOUD_TEXT");
    public static final Color CLOUD_HOVER_SURFACE = ThemeColor.of("research.CLOUD_HOVER_SURFACE");
    public static final Color CLOUD_HOVER_BORDER = ThemeColor.of("research.CLOUD_HOVER_BORDER");
    /** The remove ✕ inside a cloud chip (hover uses {@link #CLOUD_TEXT}). */
    public static final Color CLOUD_CLOSE = ThemeColor.of("research.CLOUD_CLOSE");

    private ResearchUiPalette() {
    }
}
