package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.configuration.SimplificationSettings;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.simplification.SimplificationSection;
import com.aresstack.spaceharvester.application.simplification.SimplificationUnavailableException;
import com.aresstack.spaceharvester.application.simplification.Simplifications;
import com.aresstack.spaceharvester.application.simplification.SimplifiedSentence;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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

    /** Zeichen strukturierter Zeilen (Daten, Code, Konfiguration, Tabellenzeilen aus Tika), die nie verbunden werden. */
    private static final String STRUCTURE = "\t=\"{}[]<>|;";
    /** Kürzeste Zeile, die wie ein Umbruch an der Zeilenbreite aussieht; Überschriften und Datensätze sind kürzer. */
    static final int WRAPPED_LINE = 40;
    /** Höchstens so viele Zeilen werden zu einem Satz verbunden, damit kein Satz ausufert und Abbrechen greift. */
    static final int MAX_JOINED_LINES = 10;
    /** Kennzeichen am Zeilenanfang („Betreff:“, „Von:“, „datasource:“). */
    private static final Pattern LABEL = Pattern.compile("^[\\p{L}][\\p{L}.-]{0,30}+:(?:\\s|$)");

    private final Function<PageSnapshot, Optional<PageText>> texts;
    private final KnowledgeGraphStore store;
    private final AccessGuard access;
    private final Supplier<StatementAnalyzer> analyzer;
    private final Clock clock;
    private final Simplifications simplifications;

    /**
     * @param texts    dieselbe Textprojektion wie der Leser ({@code Enrichments#text})
     * @param analyzer der eingerichtete Analysator oder {@code null} (Dienst, Bibliothek ohne {@code :nlp-parser})
     */
    public StatementAnalysis(Function<PageSnapshot, Optional<PageText>> texts, KnowledgeGraphStore store,
                             AccessGuard access, Supplier<StatementAnalyzer> analyzer, Clock clock) {
        this(texts, store, access, analyzer, clock, null);
    }

    /**
     * @param simplifications die Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3) oder {@code null} ohne sie
     */
    public StatementAnalysis(Function<PageSnapshot, Optional<PageText>> texts, KnowledgeGraphStore store,
                             AccessGuard access, Supplier<StatementAnalyzer> analyzer, Clock clock,
                             Simplifications simplifications) {
        this.simplifications = simplifications;
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
        return analyze(caller, snapshot, cancelled, null, costs -> {
        });
    }

    /**
     * Der Stand der Vorstufe, den ein Lauf mit {@code request} an der Analyse hinterlässt:
     * {@code <Betriebsart>|<Simplifications#version()>}.
     */
    public String stage(SimplificationRequest request) {
        return request.mode().value() + "|" + simplifications.version();
    }

    /**
     * Ob ein Auftrag die Seite überspringen kann: die gespeicherte Analyse ist aktuell und, wenn die Vorstufe für die
     * Seite läuft, mit demselben Stand der Vorstufe gerechnet. Ein Lauf ohne Vorstufe (oder ohne Freigabe der Seite)
     * lässt eine Analyse mit Vorstufe als aktuell stehen.
     */
    public boolean current(AccessContext caller, PageSnapshot snapshot, SimplificationRequest request) {
        PageStatements read = read(caller, snapshot);
        if (read.state() != PageStatements.State.CURRENT) {
            return false;
        }
        if (request == null || simplificationUnavailable(snapshot.key()).isPresent()) {
            return true;
        }
        return stage(request).equals(read.analysis().stage());
    }

    /** Ob die Vorstufe für diese Seite laufen kann: eingerichtet, Modell eingestellt, Seite für die KI freigegeben. */
    public Optional<String> simplificationUnavailable(PageKey key) {
        if (simplifications == null || !simplifications.available()) {
            return Optional.of(SimplificationUnavailableException.Reason.NO_MODEL.name());
        }
        if (!simplifications.released(key)) {
            return Optional.of(SimplificationUnavailableException.Reason.NOT_RELEASED.name());
        }
        return Optional.empty();
    }

    /**
     * Wie {@link #analyze(AccessContext, PageSnapshot, BooleanSupplier)}, mit der Vorstufe „Sätze vereinfachen“
     * (ADR 0032, FL3), wenn {@code request} sie verlangt und sie für die Seite laufen kann
     * ({@link #simplificationUnavailable}); sonst ohne. An das Modell gehen nur Seiten mit KI-Freigabe.
     *
     * @param request Betriebsart und Obergrenze der Vorstufe oder {@code null} ohne sie
     * @param costs   bekommt die Zahlen der Vorstufe, wenn sie lief; nie Inhalte
     */
    public PageStatements analyze(AccessContext caller, PageSnapshot snapshot, BooleanSupplier cancelled,
                                  SimplificationRequest request, Consumer<Simplifications.Costs> costs) {
        Objects.requireNonNull(costs, "costs");
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
        String documentId = PageStatementAnalysis.documentId(key, input);
        ParsedStatements result;
        String stage = null;
        if (request != null && simplificationUnavailable(key).isEmpty()) {
            stage = stage(request);
            result = parser.analyze(documentId, analyzerText(text.get()), cancelled,
                    new StatementAnalyzer.Simplification() {
                        @Override
                        public SimplificationSettings.Mode mode() {
                            return request.mode();
                        }

                        @Override
                        public Map<Integer, List<SimplifiedSentence>> simplify(List<SimplificationSection> sections) {
                            if (sections.isEmpty()) {
                                return Map.of();
                            }
                            Simplifications.Result run = simplifications.simplify(key, input, sections,
                                    request.parallel(), cancelled);
                            costs.accept(run.costs());
                            return run.sentences();
                        }
                    });
        } else {
            result = parser.analyze(documentId, analyzerText(text.get()), cancelled);
        }
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Analyse abgebrochen");
        }
        check(result, text.get().text().length());
        if (stage != null && !simplifications.released(key)) {
            // ADR 0028: Freigabe während der Analyse entzogen, kein Modelltext der Seite in den Graph; ohne die Aussagen
            // der Vorstufe fehlen die ersetzten Original-Aussagen, also gilt die Analyse als veraltet
            ParsedStatements stripped = withoutStage(result);
            stage = stripped == result ? null : PageStatementAnalysis.STAGE_WITHDRAWN;
            result = stripped;
        }
        PageStatementAnalysis analysis = new PageStatementAnalysis(key, snapshot.spaceKey(), snapshot.metadata().title(),
                snapshot.revision(), input, parser.id(), version, clock.instant(), result, null, stage);
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
        if (PageStatementAnalysis.STAGE_WITHDRAWN.equals(stored.stage())) {
            return PageStatements.STAGE_WITHDRAWN;
        }
        return null;
    }

    private void require(AccessContext caller, PageSnapshot snapshot) {
        access.require(caller, ResourceScope.page(snapshot.key().sourceId(), snapshot.spaceKey(),
                snapshot.key().pageId()), AccessKind.CONTENT);
    }

    /**
     * Der Text für den Analysator: die Textprojektion, in der jeder Zeilenumbruch eine Blockgrenze ist. Ein weicher
     * Umbruch an der Zeilenbreite mitten im Satz (Text- und PDF-Dateien) wird zum Leerzeichen, alles andere bleibt,
     * denn ohne Leerzeilen ist dort oft jede Zeile ein eigener Absatz, eine Überschrift, ein Listenpunkt oder ein
     * Datensatz. Im Zweifel bleibt der Umbruch, also die Satzgrenze. Nur innerhalb eines Absatzblocks
     * ({@link PassageSegmentation#blocks}, ADR 0020); eine Confluence-Projektion trennt Blöcke je Zeile und bleibt
     * gleich. Die Länge ändert sich nie, alle Spannen bleiben gültig.
     */
    static String analyzerText(PageText text) {
        String content = text.text();
        char[] chars = content.toCharArray();
        for (PassageSegmentation.Block block : PassageSegmentation.blocks(text)) {
            if (block.kind() != PassageSegmentation.Kind.PARAGRAPH) {
                continue;
            }
            int lineStart = block.start();
            int joined = 0;
            for (int i = content.indexOf('\n', block.start()); i >= 0 && i < block.end();
                 i = content.indexOf('\n', i + 1)) {
                String before = content.substring(lineStart, i).strip();
                int nextEnd = content.indexOf('\n', i + 1);
                String after = content.substring(i + 1, nextEnd < 0 || nextEnd > block.end() ? block.end() : nextEnd);
                if (joined + 1 < MAX_JOINED_LINES && softBreak(before, after, joined > 0)) {
                    chars[i] = ' ';
                    joined++;
                } else {
                    joined = 0;
                }
                lineStart = i + 1;
            }
        }
        return new String(chars);
    }

    /**
     * Weich ist ein Umbruch, wenn beide Zeilen Fließtext sind (kein Kennzeichen wie „Von:“, keine Zeichen aus
     * {@link #STRUCTURE}, keine gleich vielen Kommas wie in einer CSV-Zeile), die Zeile davor die Zeilenbreite
     * erreicht ({@link #WRAPPED_LINE}), einen Satz beginnt (Großbuchstabe) oder einen schon verbundenen fortsetzt,
     * nicht mit einem Satzzeichen endet, und die nächste Zeile mit einem Buchstaben beginnt: mit einem
     * kleingeschriebenen Wort, das weitere Wörter oder ein Satzende hat, oder die Zeile davor endet mit einem Komma.
     * Kleingeschriebene Datensätze (Protokolle, Glossare, Tabellenzeilen) beginnen so nie eine Verbindung.
     */
    static boolean softBreak(String before, String after, boolean continuing) {
        String next = after.strip();
        if (before.length() < WRAPPED_LINE || before.indexOf(' ') < 0 || next.isEmpty()
                || !continuing && !Character.isUpperCase(before.charAt(0))
                || !prose(before) || !prose(next) || !Character.isLetter(next.charAt(0)) || enumerator(next)) {
            return false;
        }
        char last = before.charAt(before.length() - 1);
        if (".!?:;".indexOf(last) >= 0) {
            return false;
        }
        int commas = count(before, ',');
        if (commas > 0 && commas == count(next, ',')) {
            return false; // gleich gebaute Zeilen: Datensätze, keine Fortsetzung
        }
        if (last == ',') {
            return true;
        }
        int space = next.indexOf(' ');
        char end = next.charAt(next.length() - 1);
        return Character.isLowerCase(next.charAt(0)) && (space > 0 || end == '.' || end == '!' || end == '?');
    }

    private static boolean prose(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (STRUCTURE.indexOf(line.charAt(i)) >= 0) {
                return false;
            }
        }
        return !LABEL.matcher(line).find() && !line.contains("://");
    }

    /** „a)“, „bb)“, „iv.“ am Zeilenanfang. */
    private static boolean enumerator(String line) {
        int i = 0;
        while (i < line.length() && i < 4 && Character.isLetterOrDigit(line.charAt(i))) {
            i++;
        }
        return i > 0 && i < line.length() && (line.charAt(i) == ')' || line.charAt(i) == '.' && i <= 3
                && line.substring(0, i).chars().allMatch(c -> "ivxlc".indexOf(c) >= 0 || Character.isDigit(c)));
    }

    private static int count(String line, char wanted) {
        int found = 0;
        for (int i = 0; i < line.length(); i++) {
            found += line.charAt(i) == wanted ? 1 : 0;
        }
        return found;
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

    /**
     * Entfernt nach dem Entzug der KI-Freigabe (ADR 0028) aus den gespeicherten Analysen der Seiten ohne Freigabe die
     * Aussagen der Vorstufe samt ihren Erwähnungen, ohne neu zu rechnen (der Server hat keinen Parser).
     *
     * @return Zahl der geänderten Analysen
     */
    public int withdrawStage(java.util.Collection<PageKey> pages, java.util.function.Predicate<PageKey> released) {
        int changed = 0;
        for (PageKey page : pages) {
            if (released.test(page)) {
                continue;
            }
            Optional<PageStatementAnalysis> stored = store.find(page);
            if (stored.isEmpty()) {
                continue;
            }
            ParsedStatements result = stored.get().result();
            ParsedStatements stripped = withoutStage(result);
            if (stripped != result) {
                // Die ersetzten Original-Aussagen fehlen jetzt: veraltet, der nächste Lauf rechnet ohne Vorstufe
                store.replace(stored.get().withResult(stripped).withStage(PageStatementAnalysis.STAGE_WITHDRAWN));
                changed++;
            } else if (stored.get().stage() != null
                    && !PageStatementAnalysis.STAGE_WITHDRAWN.equals(stored.get().stage())) {
                store.replace(stored.get().withStage(null)); // keine Aussage der Vorstufe übrig, nichts fehlt
                changed++;
            }
        }
        return changed;
    }

    /**
     * Die Analyse ohne Aussagen der Vorstufe und ohne die Erwähnungen, auf die nur sie zeigten; dieselbe Instanz, wenn
     * es keine gab.
     */
    static ParsedStatements withoutStage(ParsedStatements result) {
        List<ParsedStatements.Statement> kept = result.statements().stream()
                .filter(statement -> statement.derivation().origin() == ParsedStatements.Origin.ORIGINAL).toList();
        if (kept.size() == result.statements().size()) {
            return result;
        }
        Set<String> stillUsed = new HashSet<>();
        kept.forEach(statement -> statement.arguments().forEach(argument -> stillUsed.add(argument.mention())));
        result.attachments().forEach(attachment -> {
            stillUsed.add(attachment.head());
            stillUsed.add(attachment.dependent());
        });
        Set<String> onlyStage = new HashSet<>();
        result.statements().stream()
                .filter(statement -> statement.derivation().origin() != ParsedStatements.Origin.ORIGINAL)
                .forEach(statement -> statement.arguments().forEach(argument -> {
                    if (argument.mention() != null && !stillUsed.contains(argument.mention())) {
                        onlyStage.add(argument.mention());
                    }
                }));
        List<ParsedStatements.Mention> mentions = result.mentions().stream()
                .filter(mention -> !onlyStage.contains(mention.id())).toList();
        return new ParsedStatements(result.sentences(), mentions, kept, result.attachments(), result.components(),
                result.conllu());
    }

    /**
     * Die Vorstufe für einen Analyselauf (ADR 0032, FL3).
     *
     * @param mode     welche Sätze vereinfacht werden
     * @param parallel so viele Abschnitte einer Seite höchstens zugleich beim Modell
     */
    public record SimplificationRequest(SimplificationSettings.Mode mode, int parallel) {

        public SimplificationRequest {
            Objects.requireNonNull(mode, "mode");
            if (parallel < 1) {
                throw new IllegalArgumentException("parallel muss mindestens 1 sein");
            }
        }

        /** Nach den Einstellungen. */
        public static SimplificationRequest of(SimplificationSettings settings) {
            return new SimplificationRequest(settings.mode(), settings.parallel());
        }
    }
}
