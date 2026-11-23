package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.mainframe.dependency.CobolCalls;
import com.aresstack.spaceharvester.mainframe.dependency.NaturalRelations;
import com.aresstack.spaceharvester.mainframe.dependency.SourceRelations;
import com.aresstack.spaceharvester.mainframe.dependency.SystemFunctions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Was alle Editoren des Command Decks teilen und andere Bereiche einhängen: Wie in MainframeMate öffnet ein
 * Doppelklick in den Beziehungen ein Natural-Objekt über NDV ({@code MainFrame.openNaturalFromJcl} bzw. das Öffnen von
 * {@code ndv://}-Zielen). Der NDV-Bereich setzt die Öffner und den Graphen; ohne NDV bleiben sie leer und die
 * Beziehungen sagen das.
 */
public final class DocumentEditors {

    /** Öffnet ein Natural-Objekt einer Bibliothek und springt zur Zeile ({@code 0} = an den Anfang). */
    @FunctionalInterface
    public interface NaturalOpener {
        void open(String library, String object, int line);
    }

    private static volatile BiConsumer<String, String> naturalFromJcl;
    private static volatile NaturalOpener naturalOpener;
    private static volatile NaturalRelations naturalRelations = NaturalRelations.NONE;
    private static volatile CobolCalls cobolCalls = (content, name) -> List.of();
    /** Ablage der Systemfunktionen im App-Zustand (Einstellungen › Systemfunktionen). */
    public static final String SYSTEM_FUNCTIONS_KEY = "deck.system-functions";

    private static volatile SystemFunctions systemFunctions = SystemFunctions.parse(
            com.aresstack.spaceharvester.app.ui.AppState.shared().get(SYSTEM_FUNCTIONS_KEY));

    private DocumentEditors() {
    }

    /** Natural-Programm aus der JCL öffnen ({@code STACK=(LOGON steplib;programm)}); {@code null} nimmt es weg. */
    public static void naturalFromJcl(BiConsumer<String, String> opener) {
        naturalFromJcl = opener;
    }

    public static Optional<BiConsumer<String, String>> naturalFromJcl() {
        return Optional.ofNullable(naturalFromJcl);
    }

    /** Öffner für {@code ndv://LIB/OBJEKT}-Ziele aus Ressourcen und Hierarchie; {@code null} nimmt ihn weg. */
    public static void naturalOpener(NaturalOpener opener) {
        naturalOpener = opener;
    }

    public static Optional<NaturalOpener> naturalOpener() {
        return Optional.ofNullable(naturalOpener);
    }

    /** Graph der Natural-Bibliotheken (Callees/Caller, DDM-Benutzer); {@code null} = keiner. */
    public static void naturalRelations(NaturalRelations relations) {
        naturalRelations = relations == null ? NaturalRelations.NONE : relations;
    }

    /** Externe COBOL-Aufrufe (CodeAnalytics); {@code null} = keine. */
    public static void cobolCalls(CobolCalls calls) {
        cobolCalls = calls == null ? (content, name) -> List.of() : calls;
    }

    /** Bekannte Systemfunktionen (Einstellungen › Systemfunktionen). */
    public static void systemFunctions(SystemFunctions functions) {
        systemFunctions = Objects.requireNonNull(functions, "functions");
    }

    public static SystemFunctions systemFunctions() {
        return systemFunctions;
    }

    /** Die Auswertung mit dem, was gerade eingehängt ist. */
    static SourceRelations relations() {
        return new SourceRelations(naturalRelations, cobolCalls, systemFunctions);
    }
}
