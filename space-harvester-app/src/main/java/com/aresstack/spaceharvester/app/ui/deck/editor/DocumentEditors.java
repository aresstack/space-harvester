package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.mainframe.dependency.CobolCalls;
import com.aresstack.spaceharvester.mainframe.dependency.NaturalRelations;
import com.aresstack.spaceharvester.mainframe.dependency.SourceRelations;
import com.aresstack.spaceharvester.mainframe.calltree.CodeAnalyticsService;
import com.aresstack.spaceharvester.mainframe.diagram.SystemFunction;
import com.aresstack.spaceharvester.mainframe.diagram.SystemFunctions;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
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
    /** Externe COBOL-Aufrufe wie MainframeMate ({@code CodeAnalyticsService.extractExternalCalls}). */
    private static final CobolCalls CODE_ANALYTICS = (content, name) -> CodeAnalyticsService.getInstance()
            .extractExternalCalls(content, name,
                    com.aresstack.spaceharvester.mainframe.calltree.SourceLanguage.COBOL)
            .stream().map(call -> new CobolCalls.Call(call.getCallType(), call.getTargetName(), call.getLineNumber()))
            .toList();
    private static volatile CobolCalls cobolCalls = CODE_ANALYTICS;
    /** Die Systemfunktionen (Einstellungen › Diagramme › Systemfunktionen); ohne Einhängen die Vorgaben aus MM. */
    private static volatile Supplier<Map<String, SystemFunction>> systemFunctions =
            () -> SystemFunctions.lookup(SystemFunctions.DEFAULTS);

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

    /** Externe COBOL-Aufrufe; {@code null} = wieder CodeAnalytics wie MainframeMate. */
    public static void cobolCalls(CobolCalls calls) {
        cobolCalls = calls == null ? CODE_ANALYTICS : calls;
    }

    /** Woher die Systemfunktionen kommen (der Command Deck hängt den {@code SystemFunctionsStore} ein). */
    public static void systemFunctions(Supplier<Map<String, SystemFunction>> functions) {
        systemFunctions = Objects.requireNonNull(functions, "functions");
    }

    /** Wikipedia-Artikel eines Programms wie MainframeMate: deutsch, sonst englisch, sonst Suche. */
    static URI wikipedia(String program) {
        String name = program.trim().toUpperCase(Locale.ROOT);
        SystemFunction function = systemFunctions.get().get(name);
        return URI.create((function == null ? new SystemFunction(name, "", "", "") : function).wikipediaUrl());
    }

    /** Die Auswertung mit dem, was gerade eingehängt ist. */
    static SourceRelations relations() {
        Map<String, SystemFunction> known = systemFunctions.get();
        return new SourceRelations(naturalRelations, cobolCalls,
                program -> known.containsKey(program.toUpperCase(Locale.ROOT)));
    }
}
