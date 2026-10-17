package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.plaf.basic.BasicMenuItemUI;

/**
 * A menu-item UI that keeps the item completely NORMAL (font, layout, accelerator) but replaces
 * the look and feel's selection highlight with the comic palette: yellow plate, ink text. This is
 * how the design language continues into an open dropdown without burst controls on the entries.
 */
public class ComicMenuItemUI extends BasicMenuItemUI {

    private final ComicPalette palette;

    public ComicMenuItemUI(ComicPalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        selectionBackground = palette.getAccentYellow();
        selectionForeground = palette.getInk();
        acceleratorSelectionForeground = palette.getInk();
    }
}
