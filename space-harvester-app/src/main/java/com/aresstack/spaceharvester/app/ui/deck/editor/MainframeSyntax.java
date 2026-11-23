package com.aresstack.spaceharvester.app.ui.deck.editor;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * Meldet die Mainframe-Hervorhebungen bei RSyntaxTextArea an, übernommen aus MainframeMate
 * ({@code MainframeSyntaxSupport}): Natural als {@code text/natural}. JCL zeigt MainframeMate mit dem Stil
 * „Properties“ an, COBOL ohne Hervorhebung; das bleibt so.
 */
public final class MainframeSyntax {

    /** MIME-Typ für Natural (Software AG). */
    public static final String NATURAL = "text/natural";

    private static boolean registered;

    private MainframeSyntax() {
    }

    /** Meldet an; mehrfacher Aufruf schadet nicht. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        if (TokenMakerFactory.getDefaultInstance() instanceof AbstractTokenMakerFactory factory) {
            factory.putMapping(NATURAL, NaturalTokenMaker.class.getName());
            registered = true;
        }
    }
}
