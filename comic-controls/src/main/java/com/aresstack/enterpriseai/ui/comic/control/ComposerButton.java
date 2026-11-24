package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * AskAI's composer button (the private {@code ChatComposerPanel.ComposerButton} of the reference,
 * made reusable): a flat icon/text button that gets a translucent wash on hover, or — when
 * {@code primary} or {@link #setEmphasized emphasized} — a filled rounded plate in its accent
 * (the composer's {@link ResearchUiPalette#ACCENT_BLUE} by default) with white text. The glyph
 * follows the button foreground, so one {@link ComposerIcons} icon serves every state.
 *
 * <p>The static factories are the reference's toolbar recipes: the hamburger that latches dark,
 * icon-only 30×28 buttons, ribbon entries and ribbon scroll arrows.</p>
 */
public class ComposerButton extends JButton {

    private final boolean primary;
    private Color accent;
    private boolean emphasized;
    private Color glyphTint;

    public ComposerButton(Icon icon, String text, boolean primary) {
        this(icon, text, primary, null);
    }

    public ComposerButton(Icon icon, String text, boolean primary, String tooltip) {
        super(text, icon);
        this.primary = primary;
        ComposerButtonStyle.configure(this, tooltip);
    }

    /** The plate color for primary/emphasized painting ({@code null} = the composer's accent blue). */
    public void setAccent(Color accent) {
        this.accent = accent;
        repaint();
    }

    public Color getAccent() {
        return accent;
    }

    /** Latched/"pressed in": paints the filled plate like a primary button (e.g. the pinned hamburger). */
    public void setEmphasized(boolean emphasized) {
        if (this.emphasized != emphasized) {
            this.emphasized = emphasized;
            repaint();
        }
    }

    public boolean isEmphasized() {
        return emphasized;
    }

    public boolean isPrimary() {
        return primary;
    }

    /**
     * Colors the glyph of a flat (not filled) enabled button, e.g. to show a toggle's state without a plate;
     * {@code null} keeps the standard control text color.
     */
    public void setGlyphTint(Color glyphTint) {
        this.glyphTint = glyphTint;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        // The tint goes into the single foreground decision, so painting never flips the color back and forth.
        ComposerButtonStyle.paintBackground(this, graphics, accent, primary || emphasized, glyphTint);
        super.paintComponent(graphics);
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        return ComposerButtonStyle.preferredSize(super.getPreferredSize(), primary);
    }

    // ------------------------------------------------------------------ the reference's recipes

    /** The sidebar hamburger: menu glyph, latches in the dark secondary surface, 30×28. */
    public static ComposerButton sidebarToggle(String tooltip) {
        ComposerButton button = new ComposerButton(ComposerIcons.menu(), null, false, tooltip);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        button.setPreferredSize(new Dimension(30, 28));
        return button;
    }

    /** An icon-only 30×28 toolbar button in the composer style. */
    public static ComposerButton iconButton(Icon icon, String tooltip) {
        ComposerButton button = new ComposerButton(icon, null, false, tooltip);
        button.setPreferredSize(new Dimension(30, 28));
        return button;
    }

    /** A primary action (filled plate in {@code accent}, white text), e.g. Send or Stop. */
    public static ComposerButton primary(Icon icon, String text, Color accent, String tooltip) {
        ComposerButton button = new ComposerButton(icon, text, true, tooltip);
        button.setAccent(accent);
        return button;
    }

    /** One entry of the unfolding sidebar tab ribbon; the active entry fills dark. */
    public static ComposerButton ribbonEntry(String text, boolean active) {
        ComposerButton button = new ComposerButton(null, text, false, text);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        button.setEmphasized(active);
        return button;
    }

    /** A ribbon entry shown as a glyph only; the tab's name is the tooltip (and the accessible name). */
    public static ComposerButton ribbonIconEntry(Icon icon, String title, boolean active) {
        ComposerButton button = new ComposerButton(icon, null, false, title);
        button.getAccessibleContext().setAccessibleName(title);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        button.setEmphasized(active);
        button.setPreferredSize(new Dimension(34, 28));
        return button;
    }

    /** A slim ‹/› scroll arrow for the sidebar tab ribbon. */
    public static ComposerButton ribbonArrow(boolean leftDirection, String tooltip) {
        ComposerButton button = new ComposerButton(
                leftDirection ? ComposerIcons.chevronLeft() : ComposerIcons.chevronRight(), null, false,
                tooltip);
        button.setPreferredSize(new Dimension(18, 28));
        return button;
    }
}
