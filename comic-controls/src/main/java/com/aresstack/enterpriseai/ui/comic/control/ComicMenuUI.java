package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.plaf.basic.BasicMenuUI;

/**
 * The {@link ComicMenuItemUI} counterpart for SUBMENU titles inside a dropdown: normal font and
 * layout, comic yellow/ink selection. (Top-level menus use {@link ComicHoverMenu} instead.)
 */
public class ComicMenuUI extends BasicMenuUI {

    private final ComicPalette palette;

    public ComicMenuUI(ComicPalette palette) {
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
    }
}
