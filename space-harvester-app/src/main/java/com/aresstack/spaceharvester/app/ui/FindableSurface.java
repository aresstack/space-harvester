package com.aresstack.spaceharvester.app.ui;

import java.util.List;

/**
 * Eine selbst gezeichnete Fläche, in der die Lupe ({@link ReaderFind}) suchen kann, etwa das Terminalbild: Sie hat
 * keinen Swing-Text, aber einen durchsuchbaren Text und malt die Treffer selbst. Die Fläche hängt sich als
 * Client-Eigenschaft {@link #KEY} an ihre Komponente; so braucht das Modul der Fläche die App nicht zu kennen.
 */
public interface FindableSurface {

    /** Schlüssel der Client-Eigenschaft an der Komponente. */
    String KEY = FindableSurface.class.getName();

    /** Der durchsuchbare Text, so wie er gerade zu sehen ist. */
    String findText();

    /**
     * Zeigt die Treffer: je Eintrag {@code {start, end}} im Text von {@link #findText()}, {@code current} ist der
     * Index des aktuellen Treffers oder -1. Eine leere Liste nimmt alle Markierungen weg.
     */
    void showFound(List<int[]> found, int current);
}
