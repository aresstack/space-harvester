package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.JScrollPane;
import java.awt.Component;

/**
 * A scroll pane whose scrollbars speak the comic language quietly — both bars carry the shared
 * {@link ComicScrollBarUI} (no arrow buttons, transparent track, slim rounded ink thumb).
 * Scrolling behavior is untouched.
 */
public class ComicScrollPane extends JScrollPane {

    public ComicScrollPane(Component view, int vsbPolicy, int hsbPolicy) {
        this(view, vsbPolicy, hsbPolicy, ComicPalette.defaultPalette());
    }

    public ComicScrollPane(Component view, int vsbPolicy, int hsbPolicy, ComicPalette palette) {
        super(view, vsbPolicy, hsbPolicy);
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        setBorder(null);
        ComicScrollBarUI.install(getVerticalScrollBar(), palette);
        ComicScrollBarUI.install(getHorizontalScrollBar(), palette);
    }
}
