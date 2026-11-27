package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Color;

/**
 * Gemeinsame Darstellung aller Baumknoten der linken Seitenleiste (Wissensquellen, Favoriten, Ordnerbäume): ohne
 * Auswahl durchsichtig auf dem grauen Grund, gewählt Comic-Gelb mit Tinte, ohne Fokusrahmen, Schrift wie der Baum.
 * Wer Symbol oder Text je Knoten setzt, erbt von hier, statt Farben selbst zu wählen – sonst malt der Look-and-Feel
 * hinter die Schrift sein weißes {@code Tree.textBackground} (Angelo 27.11.2026, Favoriten mit weißem Hintergrund).
 */
public class ComicTreeRenderer extends DefaultTreeCellRenderer {

    /** Durchsichtig: der Hintergrund der Seitenleiste scheint durch. */
    private static final Color CLEAR = new Color(0, 0, 0, 0);

    public ComicTreeRenderer() {
        setBackgroundNonSelectionColor(CLEAR);
        setBackgroundSelectionColor(Comic.YELLOW);
        setTextNonSelectionColor(Comic.INK);
        setTextSelectionColor(Comic.INK);
        setBorderSelectionColor(null);
        setFont(Comic.regular(13f));
    }
}
