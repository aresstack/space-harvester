package com.aresstack.enterpriseai.ui.comic.theme;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import org.junit.Test;

import javax.swing.UIManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Installing the theme routes the design language through the UIManager so popups and menus the
 * library never touches directly (context menus, plugin menus) still speak it.
 */
public class ComicThemeTest {

    @Test
    public void installSetsTheComicSelectionAndPopupContour() {
        ComicTheme.installMenuDefaults();
        ComicPalette palette = ComicPalette.defaultPalette();

        assertTrue(UIManager.get("PopupMenu.border") instanceof ComicBorder);
        assertEquals(palette.getAccentYellow(), UIManager.getColor("MenuItem.selectionBackground"));
        assertEquals(palette.getInk(), UIManager.getColor("MenuItem.selectionForeground"));
        assertEquals(palette.getAccentYellow(), UIManager.getColor("Menu.selectionBackground"));
        assertTrue("the menu bar gets its ink baseline",
                UIManager.getBorder("MenuBar.border") != null);
    }
}
