package com.aresstack.enterpriseai.ui.comic.theme;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;

/**
 * One-shot installation of the comic design language into the {@link UIManager} defaults, so the
 * language reaches components the library does not wrap individually: EVERY dropdown and context
 * popup gets the ink contour and the yellow/ink selection, and the menu bar gets its ink baseline.
 * Surfaces stay calm — this installs contours and selection accents, no burst backgrounds.
 *
 * <p>Call once at startup, after any look-and-feel setup and before the UI is built.</p>
 */
public final class ComicTheme {

    private ComicTheme() {
    }

    public static void installMenuDefaults() {
        installMenuDefaults(ComicPalette.defaultPalette());
    }

    public static void installMenuDefaults(ComicPalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        ColorUIResource selectionBackground = new ColorUIResource(palette.getAccentYellow());
        ColorUIResource selectionForeground = new ColorUIResource(palette.getInk());

        UIManager.put("PopupMenu.border", ComicBorder.popupBorder(palette));
        UIManager.put("MenuBar.border",
                BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getInk()));

        String[] itemKinds = {"MenuItem", "Menu", "CheckBoxMenuItem", "RadioButtonMenuItem"};
        for (String kind : itemKinds) {
            UIManager.put(kind + ".selectionBackground", selectionBackground);
            UIManager.put(kind + ".selectionForeground", selectionForeground);
        }
        UIManager.put("MenuItem.acceleratorSelectionForeground", selectionForeground);
    }
}
