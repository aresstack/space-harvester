package com.aresstack.enterpriseai.ui.comic.theme;

import java.awt.Color;

/**
 * The comic design language's color roles in one replaceable value object. The default palette
 * mirrors the color family the chat bubbles already use, so both worlds read as one design:
 *
 * <ul>
 *   <li>quiet neutral surfaces, dark "ink" for outlines and text,</li>
 *   <li>blue for user/navigation, petrol for the agent,</li>
 *   <li>yellow/orange for action, hint and hover accents,</li>
 *   <li>red for critical actions and problems.</li>
 * </ul>
 *
 * <p>The impact accents (yellow/orange/red) are meant for INTERACTIVE moments (e.g. hover), never
 * as standard backgrounds — surfaces stay calm.</p>
 */
public final class ComicPalette {

    private final Color surface;
    private final Color ink;
    private final Color navigationBlue;
    private final Color agentPetrol;
    private final Color accentYellow;
    private final Color accentOrange;
    private final Color accentRed;

    public ComicPalette(Color surface,
                        Color ink,
                        Color navigationBlue,
                        Color agentPetrol,
                        Color accentYellow,
                        Color accentOrange,
                        Color accentRed) {
        this.surface = requireColor(surface, "surface");
        this.ink = requireColor(ink, "ink");
        this.navigationBlue = requireColor(navigationBlue, "navigationBlue");
        this.agentPetrol = requireColor(agentPetrol, "agentPetrol");
        this.accentYellow = requireColor(accentYellow, "accentYellow");
        this.accentOrange = requireColor(accentOrange, "accentOrange");
        this.accentRed = requireColor(accentRed, "accentRed");
    }

    /** The default AskAI comic palette (same family as the Windows-Phone-inspired chat bubbles). */
    public static ComicPalette defaultPalette() {
        return new ComicPalette(
                new Color(0xF4F4F4),
                new Color(0x252525),
                new Color(0x1676D2),
                new Color(0x15827A),
                new Color(0xF2C94C),
                new Color(0xE39A18),
                new Color(0xC94C4C));
    }

    public Color getSurface() {
        return surface;
    }

    public Color getInk() {
        return ink;
    }

    public Color getNavigationBlue() {
        return navigationBlue;
    }

    public Color getAgentPetrol() {
        return agentPetrol;
    }

    public Color getAccentYellow() {
        return accentYellow;
    }

    public Color getAccentOrange() {
        return accentOrange;
    }

    public Color getAccentRed() {
        return accentRed;
    }

    private static Color requireColor(Color color, String name) {
        if (color == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return color;
    }
}
