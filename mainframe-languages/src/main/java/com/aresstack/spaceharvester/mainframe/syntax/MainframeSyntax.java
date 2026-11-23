package com.aresstack.spaceharvester.mainframe.syntax;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Meldet die Hervorhebungen für Natural, JCL und COBOL bei RSyntaxTextArea an (wie {@code MainframeSyntaxSupport} in
 * MainframeMate, dort nur Natural). Mehrfaches Aufrufen schadet nicht.
 */
public final class MainframeSyntax {

    public static final String NATURAL = "text/natural";
    public static final String JCL = "text/jcl";
    public static final String COBOL = "text/cobol";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private MainframeSyntax() {
    }

    public static void register() {
        if (TokenMakerFactory.getDefaultInstance() instanceof AbstractTokenMakerFactory factory
                && REGISTERED.compareAndSet(false, true)) {
            factory.putMapping(NATURAL, NaturalTokenMaker.class.getName(), MainframeSyntax.class.getClassLoader());
            factory.putMapping(JCL, JclTokenMaker.class.getName(), MainframeSyntax.class.getClassLoader());
            factory.putMapping(COBOL, CobolTokenMaker.class.getName(), MainframeSyntax.class.getClassLoader());
        }
    }
}
