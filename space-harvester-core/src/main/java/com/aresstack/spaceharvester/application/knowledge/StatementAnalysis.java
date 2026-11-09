package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Aussagenanalyse einer einzelnen Seite auf ausdrückliche Anforderung (#499, ADR 0024). Analysiert genau die
 * Textprojektion, die der Leser im Reiter „Text“ zeigt, und ersetzt die gespeicherte Analyse der Seite samt ihrer
 * Graph-Projektion in einem Schritt. Kein anderer Ablauf ruft {@link #analyze}: weder Abgleich, Cache-Füllung,
 * Suche, Export noch „Analysen neu berechnen“; Lesen ({@link #read}) rechnet nie.
 *
 * <p>Fehler und Abbruch schreiben nichts: Die Ablage sieht entweder die alte oder die vollständige neue Analyse.
 * Ändert sich der Text oder der Analysator, gilt die gespeicherte Analyse als {@link PageStatements.State#STALE},
 * bis der Nutzer erneut klickt.
 */
public class StatementAnalysis {

    private final Function<PageSnapshot, Optional<PageText>> texts;
    private final KnowledgeGraphStore store;
    private final AccessGuard access;
    private final Supplier<StatementAnalyzer> analyzer;
    private final Clock clock;

    /**
     * @param texts    dieselbe Textprojektion wie der Leser ({@code Enrichments#text})
     * @param analyzer der eingerichtete Analysator oder {@code null} (Dienst, Bibliothek ohne {@code :nlp-parser})
     */
    public StatementAnalysis(Function<PageSnapshot, Optional<PageText>> texts, KnowledgeGraphStore store,
                             AccessGuard access, Supplier<StatementAnalyzer> analyzer, Clock clock) {
        this.texts = Objects.requireNonNull(texts, "texts");
        this.store = Objects.requireNonNull(store, "store");
        this.access = Objects.requireNonNull(access, "access");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Ob in dieser Laufzeit ein Analysator eingerichtet ist; lädt kein Modell. */
    public boolean available() {
        return analyzer.get() != null;
    }

    /**
     * Die gespeicherte Analyse der Seite mit ihrem Stand; rechnet nie und lädt kein Modell.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Leserecht auf die Seite
     */
    public PageStatements read(AccessContext caller, PageSnapshot snapshot) {
        require(caller, snapshot);
        Optional<PageStatementAnalysis> stored = store.find(snapshot.key());
        if (stored.isEmpty()) {
            return PageStatements.missing(texts.apply(snapshot).isEmpty() ? PageStatements.NO_TEXT
                    : PageStatements.NOT_ANALYSED);
        }
        String reason = reason(stored.get(), texts.apply(snapshot).map(PageText::inputSha256));
        return new PageStatements(reason == null ? PageStatements.State.CURRENT : PageStatements.State.STALE, reason,
                stored.get());
    }

    /**
     * Die vollständige UD-Analyse (CoNLL-U) der gespeicherten Analyse, für die aufklappbaren Syntaxdetails.
     */
    public Optional<String> syntaxDetails(AccessContext caller, PageSnapshot snapshot) {
        require(caller, snapshot);
        return store.conllu(snapshot.key());
    }

    /**
     * Analysiert die Seite jetzt, im aufrufenden Thread, und ersetzt die gespeicherte Analyse. Erneut auslösbar; bei
     * unverändertem Stand entsteht dasselbe Ergebnis ohne Duplikate.
     *
     * @param cancelled wird zwischen den Sätzen gefragt; {@code true} bricht ohne Schreiben ab
     * @return der neue Stand ({@link PageStatements.State#CURRENT}) oder {@link PageStatements#NO_TEXT} ohne Text
     * @throws AnalyzerUnavailableException wenn kein Analysator eingerichtet ist oder das Modell fehlt bzw. defekt ist
     * @throws CancellationException        nach einem Abbruch; die alte Analyse bleibt
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Leserecht auf die Seite
     */
    public PageStatements analyze(AccessContext caller, PageSnapshot snapshot, BooleanSupplier cancelled) {
        require(caller, snapshot);
        StatementAnalyzer parser = analyzer.get();
        if (parser == null) {
            throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NOT_CONFIGURED,
                    "In dieser Laufzeit ist kein Satzanalysator eingerichtet (nur in der Desktop-App)", null);
        }
        Optional<PageText> text = texts.apply(snapshot);
        if (text.isEmpty()) {
            return PageStatements.missing(PageStatements.NO_TEXT);
        }
        PageKey key = snapshot.key();
        String input = text.get().inputSha256();
        // Die Version steht vor dem Rechnen fest: wechselt das Modell währenddessen, ist das Ergebnis veraltet.
        String version = parser.version();
        ParsedStatements result = parser.analyze(PageStatementAnalysis.documentId(key, input), text.get().text(),
                cancelled);
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Analyse abgebrochen");
        }
        check(result, text.get().text().length());
        PageStatementAnalysis analysis = new PageStatementAnalysis(key, snapshot.spaceKey(), snapshot.metadata().title(),
                snapshot.revision(), input, parser.id(), version, clock.instant(), result);
        store.replace(analysis);
        return new PageStatements(PageStatements.State.CURRENT, null,
                analysis.withResult(result.withoutConllu()));
    }

    /** Warum eine gespeicherte Analyse veraltet ist, oder {@code null}, wenn sie aktuell ist. */
    String reason(PageStatementAnalysis stored, Optional<String> input) {
        if (input.isEmpty()) {
            return PageStatements.NO_TEXT;
        }
        if (!stored.inputSha256().equals(input.get())) {
            return PageStatements.TEXT_CHANGED;
        }
        StatementAnalyzer parser = analyzer.get();
        if (parser != null && !(parser.id().equals(stored.analyzerId())
                && parser.version().equals(stored.analyzerVersion()))) {
            return PageStatements.ANALYZER_CHANGED;
        }
        return null;
    }

    private void require(AccessContext caller, PageSnapshot snapshot) {
        access.require(caller, ResourceScope.page(snapshot.key().sourceId(), snapshot.spaceKey(),
                snapshot.key().pageId()), AccessKind.CONTENT);
    }

    /** Ein Analysator darf nur Spannen im Text und Beteiligte auf eigene Erwähnungen liefern; sonst wird nichts gespeichert. */
    static void check(ParsedStatements result, int length) {
        Set<String> mentions = new HashSet<>();
        for (ParsedStatements.Mention mention : result.mentions()) {
            if (mention.start() < 0 || mention.end() > length || mention.end() < mention.start()) {
                throw new IllegalStateException("Erwähnung außerhalb des Texts");
            }
            mentions.add(mention.id());
        }
        for (ParsedStatements.Statement statement : result.statements()) {
            if (statement.start() < 0 || statement.end() > length || statement.end() < statement.start()) {
                throw new IllegalStateException("Prädikat außerhalb des Texts");
            }
            for (ParsedStatements.Argument argument : statement.arguments()) {
                if (!mentions.contains(argument.mention())) {
                    throw new IllegalStateException("Beteiligter ohne Erwähnung");
                }
            }
        }
        for (ParsedStatements.Attachment attachment : result.attachments()) {
            if (!mentions.contains(attachment.head()) || !mentions.contains(attachment.dependent())) {
                throw new IllegalStateException("Nominaler Bezug ohne Erwähnung");
            }
        }
    }
}
