package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.check.CheckScope;
import com.aresstack.spaceharvester.application.check.CheckUnavailableException;
import com.aresstack.spaceharvester.application.check.Corrections;
import com.aresstack.spaceharvester.application.check.StatementCheck;
import com.aresstack.spaceharvester.application.check.StatementChecks;
import com.aresstack.spaceharvester.application.configuration.SimplificationSettings;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.evidence.CurrentSources;
import com.aresstack.spaceharvester.application.evidence.SourceLocationCheck;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.simplification.SimplificationSection;
import com.aresstack.spaceharvester.application.simplification.SimplificationUnavailableException;
import com.aresstack.spaceharvester.application.simplification.Simplifications;
import com.aresstack.spaceharvester.application.simplification.SimplifiedSentence;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
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
    private final StatementChecks checks;
    private final Supplier<StatementAnalyzer> model;
    private final java.util.function.Predicate<PageKey> released;
    private final StatementReviews reviews;

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
        this(texts, store, access, analyzer, clock, simplifications, null);
    }

    /**
     * @param checks der Prüfschritt (ADR 0032, FL4) oder {@code null} ohne ihn
     */
    public StatementAnalysis(Function<PageSnapshot, Optional<PageText>> texts, KnowledgeGraphStore store,
                             AccessGuard access, Supplier<StatementAnalyzer> analyzer, Clock clock,
                             Simplifications simplifications, StatementChecks checks) {
        this(texts, store, access, analyzer, clock, simplifications, checks, () -> null, page -> false);
    }

    /**
     * @param model    der KI-Analysator (Fahrplan Paket A2), Ergebnis {@code null} ohne ihn
     * @param released ob eine Seite jetzt an den KI-Dienst darf, etwa {@code AiRelease::mayProcess} (ADR 0028)
     */
    public StatementAnalysis(Function<PageSnapshot, Optional<PageText>> texts, KnowledgeGraphStore store,
                             AccessGuard access, Supplier<StatementAnalyzer> analyzer, Clock clock,
                             Simplifications simplifications, StatementChecks checks,
                             Supplier<StatementAnalyzer> model, java.util.function.Predicate<PageKey> released) {
        this(texts, store, access, analyzer, clock, simplifications, checks, model, released, StatementReviews.NONE);
    }

    /**
     * @param reviews die Prüfung durch Menschen (ADR 0032, Umsetzung W3), die Lesewege über die gespeicherte Analyse
     *                legen; auch {@link KnowledgeGraph} nimmt sie von hier, damit es genau eine Stelle gibt
     */
    public StatementAnalysis(Function<PageSnapshot, Optional<PageText>> texts, KnowledgeGraphStore store,
                             AccessGuard access, Supplier<StatementAnalyzer> analyzer, Clock clock,
                             Simplifications simplifications, StatementChecks checks,
                             Supplier<StatementAnalyzer> model, java.util.function.Predicate<PageKey> released,
                             StatementReviews reviews) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.simplifications = simplifications;
        this.checks = checks;
        this.model = Objects.requireNonNull(model, "model");
        this.released = Objects.requireNonNull(released, "released");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.store = Objects.requireNonNull(store, "store");
        this.access = Objects.requireNonNull(access, "access");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Die Zeit der Uhr, mit der {@link PageStatementAnalysis#analyzedAt()} geschrieben wird. */
    public java.time.Instant now() {
        return clock.instant();
    }

    /** Ob in dieser Laufzeit ein Analysator eingerichtet ist; lädt kein Modell. */
    public boolean available() {
        return analyzer.get() != null;
    }

    /** Die Prüfung durch Menschen für die Lesewege ({@link KnowledgeGraph}); nie vor einem Zurückschreiben. */
    StatementReviews reviews() {
        return reviews;
    }

    /** Die Analyse mit der wirksamen Prüfung durch Menschen; nur für Rückgaben, nie für die Ablage. */
    private PageStatementAnalysis reviewed(PageStatementAnalysis analysis) {
        return reviews.snapshot().apply(analysis);
    }

    /**
     * Die gespeicherte Analyse der Seite mit ihrem Stand und der wirksamen Prüfung durch Menschen
     * ({@link StatementReviews}); rechnet nie und lädt kein Modell.
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
                reviewed(stored.get()));
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
        PageStatements read = read(caller, snapshot, AnalyzerChoice.PARSER);
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
        Optional<PageStatementAnalysis> program = store(AnalyzerChoice.PARSER).find(key)
                .filter(StatementAnalysis::ofProgram);
        if (program.isPresent()) {
            // ADR 0041, Punkt 7: Programmcode ist kein Satztext; seine Aussagen kommen aus der Programmanalyse und
            // werden von der Satzanalyse nie überschrieben
            return read(caller, snapshot);
        }
        String input = text.get().inputSha256();
        // Die Version steht vor dem Rechnen fest: wechselt das Modell währenddessen, ist das Ergebnis veraltet.
        String version = parser.version();
        String documentId = PageStatementAnalysis.documentId(key, input);
        ParsedStatements result;
        String stage = null;
        Map<Integer, ParsedStatements.Section> stageSections = new java.util.concurrent.ConcurrentHashMap<>();
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
                            for (SimplificationSection section : sections) {
                                List<SimplificationSection.Sentence> lines = section.lines();
                                ParsedStatements.Section range = new ParsedStatements.Section(lines.get(0).index(),
                                        lines.get(lines.size() - 1).index());
                                section.sentences().forEach(sentence -> stageSections.put(sentence.index(), range));
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
        result = withSections(result, stageSections);
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
                reviewed(analysis.withResult(result.withoutConllu())));
    }

    /**
     * Speichert KI-Hypothesen der Programmanalyse als Aussagen des Programms im Fachlogik-Bestand (ADR 0041, Punkt 7;
     * ADR 0032, Fortschreibung vom 26.11.2026). Kein zweiter Bestand: Die Aussagen liegen in der Analyse des Programms
     * wie Aussagen einer Seite, Prüfung durch Menschen wie in W3.
     *
     * <p>Jede Hypothese wird wie eine Zeile der Vorstufe mit dem Satzanalysator zerlegt; ihre Aussagen tragen die
     * Herkunft {@link ParsedStatements.Origin#PROGRAMM} und ihren Quellstellenbeleg, nie den Hypothesensatz als Beleg.
     * Nur Hypothesen, deren Beleg zu diesem Programm gehört und technisch aktuell ist
     * ({@link SourceLocationCheck}), kommen hinein; die übrigen werden gezählt, nie gespeichert. Die Fassung in den
     * Aussagen-IDs fasst Programmtext und die Fingerabdrücke aller Belege zusammen: Ändert sich Code, ein Copybook oder
     * die Analyseversion, entstehen neue IDs, und eine frühere Bestätigung wandert nicht mit. Die Analyse gilt als
     * veraltet, sobald sich der Programmtext oder der Satzanalysator ändert. Ersetzt die gespeicherte Analyse des
     * Programms in einem Schritt; Fehler und Abbruch schreiben nichts.
     *
     * @param program    das Programm; der Aufrufer braucht Leserecht
     * @param hypotheses Hypothesen mit Quellstellenbeleg, in der Reihenfolge der Programmanalyse
     * @param sources    jetziger Stand für die technische Belegprüfung
     * @throws AnalyzerUnavailableException ohne Satzanalysator
     * @throws CancellationException        nach einem Abbruch; die alte Analyse bleibt
     */
    public ProgramStatements analyzeProgram(AccessContext caller, PageSnapshot program,
                                            List<ProgramHypothesis> hypotheses, CurrentSources sources,
                                            BooleanSupplier cancelled) {
        Objects.requireNonNull(hypotheses, "hypotheses");
        Objects.requireNonNull(sources, "sources");
        require(caller, program);
        StatementAnalyzer parser = analyzer.get();
        if (parser == null) {
            throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NOT_CONFIGURED,
                    "In dieser Laufzeit ist kein Satzanalysator eingerichtet (nur in der Desktop-App)", null);
        }
        Optional<PageText> text = texts.apply(program);
        if (text.isEmpty()) {
            return new ProgramStatements(PageStatements.missing(PageStatements.NO_TEXT), 0, hypotheses.size());
        }
        PageKey key = program.key();
        DocumentKey document = DocumentKey.page(key);
        List<String> lines = new ArrayList<>();
        List<SourceLocationEvidence> evidence = new ArrayList<>();
        int rejected = 0;
        for (ProgramHypothesis hypothesis : hypotheses) {
            if (!hypothesis.source().program().equals(document)
                    || !SourceLocationCheck.check(hypothesis.source(), sources).current()) {
                rejected++;
                continue;
            }
            lines.add(hypothesis.sentence());
            evidence.add(hypothesis.source());
        }
        String input = text.get().inputSha256();
        String version = parser.version();
        String hypothesisText = String.join("\n", lines);
        StringBuilder basis = new StringBuilder(input).append('\n').append(hypothesisText);
        evidence.forEach(source -> basis.append('\n').append(source.fingerprint()));
        ParsedStatements parsed = lines.isEmpty() ? new ParsedStatements(List.of(), List.of(), List.of(), List.of(),
                List.of(), null) : parser.analyze(PageStatementAnalysis.documentId(key, sha256(basis.toString())),
                hypothesisText, cancelled);
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Analyse abgebrochen");
        }
        check(parsed, hypothesisText.length());
        List<ParsedStatements.Statement> statements = new ArrayList<>();
        for (ParsedStatements.Statement statement : parsed.statements()) {
            int line = line(hypothesisText, statement.start());
            statements.add(statement.withDerivation(ParsedStatements.Derivation.programm(lines.get(line),
                    evidence.get(line))));
        }
        ParsedStatements result = new ParsedStatements(parsed.sentences(), parsed.mentions(), statements,
                parsed.attachments(), parsed.components(), parsed.conllu());
        PageStatementAnalysis analysis = new PageStatementAnalysis(key, program.spaceKey(),
                program.metadata().title(), program.revision(), input, parser.id(), version, clock.instant(), result,
                null, null);
        store(AnalyzerChoice.PARSER).replace(analysis);
        return new ProgramStatements(new PageStatements(PageStatements.State.CURRENT, null,
                reviewed(analysis.withResult(result.withoutConllu()))), lines.size(), rejected);
    }

    /** Ob eine gespeicherte Analyse die Aussagen eines Programms hält (ADR 0041, Punkt 7). */
    static boolean ofProgram(PageStatementAnalysis analysis) {
        return analysis.result().statements().stream()
                .anyMatch(statement -> statement.derivation().origin() == ParsedStatements.Origin.PROGRAMM);
    }

    /** Index der Zeile (Hypothese), in der eine Zeichenposition liegt. */
    private static int line(String text, int position) {
        int line = 0;
        for (int i = text.indexOf('\n'); i >= 0 && i < position; i = text.indexOf('\n', i + 1)) {
            line++;
        }
        return line;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Eine KI-Hypothese der Programmanalyse mit ihrem Quellstellenbeleg.
     *
     * @param sentence die Hypothese als ein deutscher Satz; Zeilenumbrüche werden Leerzeichen
     * @param source   ihr Quellstellenbeleg
     */
    public record ProgramHypothesis(String sentence, SourceLocationEvidence source) {

        public ProgramHypothesis {
            Objects.requireNonNull(sentence, "sentence");
            Objects.requireNonNull(source, "source");
            sentence = sentence.replaceAll("\\s+", " ").strip();
            if (sentence.isEmpty()) {
                throw new IllegalArgumentException("Hypothese ohne Satz");
            }
        }
    }

    /**
     * Ergebnis von {@link #analyzeProgram}.
     *
     * @param statements der neue Stand
     * @param stored     gespeicherte Hypothesen
     * @param rejected   ausgelassene Hypothesen: Beleg fremd, veraltet oder nicht prüfbar (oder kein Programmtext)
     */
    public record ProgramStatements(PageStatements statements, int stored, int rejected) {
    }

    /** Der Analysator der Wahl oder {@code null}, wenn er in dieser Laufzeit fehlt; lädt kein Modell. */
    StatementAnalyzer analyzer(AnalyzerChoice choice) {
        return choice == AnalyzerChoice.MODEL ? model.get() : analyzer.get();
    }

    /** Ob der Analysator der Wahl in dieser Laufzeit eingerichtet ist (Fahrplan Paket A2); lädt kein Modell. */
    public boolean available(AnalyzerChoice choice) {
        return analyzer(choice) != null;
    }

    /** Aus welcher Ablage Graph, Stand, Begriffe und Export lesen (Fahrplan Paket A2). */
    public AnalyzerChoice selected() {
        return store instanceof AnalyzerStores stores ? stores.selected() : AnalyzerChoice.PARSER;
    }

    /** Die Ablage eines Analysators; ohne getrennte Ablagen (Tests) die eine. */
    private KnowledgeGraphStore store(AnalyzerChoice choice) {
        return store instanceof AnalyzerStores stores ? stores.store(choice) : store;
    }

    /** Alle Ablagen, jede einmal. */
    private List<KnowledgeGraphStore> stores() {
        return store instanceof AnalyzerStores stores
                ? List.of(stores.store(AnalyzerChoice.PARSER), stores.store(AnalyzerChoice.MODEL)) : List.of(store);
    }

    /**
     * Warum der KI-Analysator (Fahrplan Paket A2) diese Seite jetzt nicht rechnen kann: Seite ohne KI-Freigabe
     * ({@link AnalyzerUnavailableException.Reason#NOT_RELEASED}), sonst nicht eingerichtet oder kein Chat-Modell
     * eingestellt ({@link AnalyzerUnavailableException.Reason#NO_AI_MODEL}); leer, wenn er kann. Beides ist „bewusst
     * ausgelassen“, kein Fehler. Die Freigabe gilt unabhängig vom Modell (W5): Eine Seite ohne Freigabe zählt auch ohne
     * Chat-Modell als „ohne KI-Freigabe“, damit Vorschau, Planer und Lauf dieselben Zahlen nennen.
     */
    public Optional<AnalyzerUnavailableException.Reason> modelUnavailable(PageKey key) {
        if (!released.test(key)) {
            return Optional.of(AnalyzerUnavailableException.Reason.NOT_RELEASED);
        }
        if (!modelReady()) {
            return Optional.of(AnalyzerUnavailableException.Reason.NO_AI_MODEL);
        }
        return Optional.empty();
    }

    /** Ob der KI-Analysator eingerichtet und ein Chat-Modell bereit ist; lädt kein Modell und ruft keins. */
    public boolean modelReady() {
        StatementAnalyzer analyzer = model.get();
        return analyzer != null && analyzer.ready();
    }

    /**
     * Die gespeicherte Analyse der Seite aus der Ablage des gewählten Analysators (Fahrplan Paket A2), mit ihrem Stand
     * und der wirksamen Prüfung durch Menschen für genau diese Ablage; rechnet nie und lädt kein Modell.
     */
    public PageStatements read(AccessContext caller, PageSnapshot snapshot, AnalyzerChoice choice) {
        require(caller, snapshot);
        Optional<PageStatementAnalysis> stored = store(choice).find(snapshot.key())
                .filter(analysis -> AnalyzerChoice.of(analysis.analyzerId()) == choice);
        if (stored.isEmpty()) {
            return PageStatements.missing(texts.apply(snapshot).isEmpty() ? PageStatements.NO_TEXT
                    : PageStatements.NOT_ANALYSED);
        }
        String reason = reason(stored.get(), texts.apply(snapshot).map(PageText::inputSha256));
        return new PageStatements(reason == null ? PageStatements.State.CURRENT : PageStatements.State.STALE, reason,
                reviewed(stored.get()));
    }

    /** Ob die Analyse des gewählten Analysators aktuell ist, so dass ein Auftrag die Seite überspringen kann. */
    public boolean currentWith(AccessContext caller, PageSnapshot snapshot, AnalyzerChoice choice) {
        return read(caller, snapshot, choice).state() == PageStatements.State.CURRENT;
    }

    /**
     * Analysiert die Seite jetzt mit dem gewählten Analysator, im aufrufenden Thread, und ersetzt nur dessen gespeicherte
     * Analyse (Fahrplan Paket A2); die des anderen bleibt. {@link AnalyzerChoice#PARSER} wie
     * {@link #analyze(AccessContext, PageSnapshot, BooleanSupplier)}. {@link AnalyzerChoice#MODEL} schickt den Text nur
     * mit KI-Freigabe an den KI-Dienst und fragt die Freigabe vor und nach dem Aufruf; Fehler, Abbruch oder Entzug
     * schreiben nichts.
     *
     * @param costs bekommt die Zahlen des KI-Analysators; nie Inhalte
     * @throws AnalyzerUnavailableException ohne Analysator, ohne Chat-Modell oder ohne KI-Freigabe der Seite
     * @throws CancellationException        nach einem Abbruch; die alte Analyse bleibt
     */
    public PageStatements analyzeWith(AccessContext caller, PageSnapshot snapshot, BooleanSupplier cancelled,
                                      AnalyzerChoice choice, Consumer<StatementAnalyzer.Costs> costs) {
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(costs, "costs");
        if (choice == AnalyzerChoice.PARSER) {
            return analyze(caller, snapshot, cancelled);
        }
        require(caller, snapshot);
        PageKey key = snapshot.key();
        Optional<AnalyzerUnavailableException.Reason> unavailable = modelUnavailable(key);
        if (unavailable.isPresent()) {
            throw new AnalyzerUnavailableException(unavailable.get(), unavailable.get()
                    == AnalyzerUnavailableException.Reason.NOT_RELEASED ? "Seite nicht für den KI-Dienst freigegeben"
                    : "Kein Chat-Modell eingestellt (Einstellungen › Modelle)", null);
        }
        StatementAnalyzer analyzer = model.get();
        Optional<PageText> text = texts.apply(snapshot);
        if (text.isEmpty()) {
            return PageStatements.missing(PageStatements.NO_TEXT);
        }
        String input = text.get().inputSha256();
        String version = analyzer.version();
        // Freigabe vor jedem Modellaufruf: ein Entzug während des Laufs schickt keinen weiteren Abschnitt mehr
        ParsedStatements result = analyzer.analyze(PageStatementAnalysis.documentId(key, input),
                analyzerText(text.get()), cancelled, () -> released.test(key), costs);
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Analyse abgebrochen");
        }
        if (!released.test(key)) {
            // ADR 0028: Freigabe während des Laufs entzogen, kein Modellergebnis der Seite in die Ablage
            throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NOT_RELEASED,
                    "Seite nicht für den KI-Dienst freigegeben", null);
        }
        check(result, text.get().text().length());
        PageStatementAnalysis analysis = new PageStatementAnalysis(key, snapshot.spaceKey(), snapshot.metadata().title(),
                snapshot.revision(), input, analyzer.id(), version, clock.instant(), result, null, null);
        store(AnalyzerChoice.MODEL).replace(analysis);
        return new PageStatements(PageStatements.State.CURRENT, null,
                reviewed(analysis.withResult(result.withoutConllu())));
    }

    /**
     * Nimmt nach dem Entzug der KI-Freigabe (ADR 0028) die Analysen des KI-Analysators (Fahrplan Paket A2) der Seiten
     * ohne Freigabe zurück; die des MaltParsers bleiben.
     *
     * @return Zahl der entfernten Analysen
     */
    public int withdrawModel(java.util.Collection<PageKey> pages, java.util.function.Predicate<PageKey> released) {
        KnowledgeGraphStore modelStore = store(AnalyzerChoice.MODEL);
        int removed = 0;
        for (PageKey page : pages) {
            if (released.test(page)) {
                continue;
            }
            Optional<PageStatementAnalysis> stored = modelStore.find(page);
            if (stored.isPresent() && AnalyzerChoice.of(stored.get().analyzerId()) == AnalyzerChoice.MODEL) {
                modelStore.remove(page);
                removed++;
            }
        }
        return removed;
    }

    /** Ob der Prüfschritt eingerichtet ist und ein Chat-Modell eingestellt ist; ruft kein Modell. */
    public boolean checkAvailable() {
        return checks != null && checks.available();
    }

    /** Ob der Prüfschritt für diese Seite laufen kann: eingerichtet, Modell eingestellt, Seite für die KI freigegeben. */
    public Optional<CheckUnavailableException.Reason> checkUnavailable(PageKey key) {
        if (checks == null || !checks.available()) {
            return Optional.of(CheckUnavailableException.Reason.NO_MODEL);
        }
        if (!checks.released(key)) {
            return Optional.of(CheckUnavailableException.Reason.NOT_RELEASED);
        }
        return Optional.empty();
    }

    /**
     * Prüft die gespeicherte, aktuelle Analyse der Seite mit dem Chat-Modell (ADR 0032, FL4), im aufrufenden Thread.
     * Nur auf ausdrücklichen Auftrag und nur für Seiten mit KI-Freigabe.
     *
     * <ul>
     *     <li>Vorher fallen alle Urteile und Korrekturen eines früheren Laufs weg ({@link #withoutChecks}), auch die
     *     eines anderen Modells oder einer anderen Anweisung.</li>
     *     <li>Jede gewählte Aussage ({@link CheckScope}) mit gültigem Urteil bekommt ein
     *     {@link ParsedStatements.Check}; die Stufe des Parsers bleibt, das Urteil senkt nur die wirksame Stufe
     *     ({@link ParsedStatements.Statement#effectiveQuality()}). STIMMT hebt nie.</li>
     *     <li>Korrekturen, die den Wortabgleich bestanden, parst der Satzanalysator (ohne ihn gibt es keine), und
     *     {@link Corrections#merge} übernimmt die gültigen als eigene Aussagen. UNVOLLSTAENDIG ohne gültige Korrektur
     *     ändert nichts an der Stufe.</li>
     *     <li>Fehler, Abbruch oder Entzug der Freigabe schreiben nichts an der Analyse; schon gelesene Urteile bleiben
     *     in der Ablage des Prüfschritts für den nächsten Lauf.</li>
     * </ul>
     *
     * @param costs bekommt die Zahlen des Laufs; nie Inhalte
     * @throws CheckUnavailableException ohne Modell, ohne Freigabe oder ohne aktuelle Analyse
     * @throws CancellationException     nach einem Abbruch
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Leserecht auf die Seite
     */
    public PageStatements check(AccessContext caller, PageSnapshot snapshot, BooleanSupplier cancelled,
                                CheckRequest request, Consumer<CheckCosts> costs) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(costs, "costs");
        require(caller, snapshot);
        PageKey key = snapshot.key();
        Optional<CheckUnavailableException.Reason> unavailable = checkUnavailable(key);
        if (unavailable.isPresent()) {
            throw new CheckUnavailableException(unavailable.get());
        }
        Optional<PageStatementAnalysis> stored = store.find(key);
        Optional<PageText> text = texts.apply(snapshot);
        if (stored.isEmpty() || text.isEmpty()
                || reason(stored.get(), text.map(PageText::inputSha256)) != null) {
            throw new CheckUnavailableException(CheckUnavailableException.Reason.NOT_CURRENT);
        }
        PageStatementAnalysis analysis = stored.get();
        ParsedStatements base = withoutChecks(analysis.result());
        String content = text.get().text();
        Map<String, ParsedStatements.Mention> mentions = new java.util.HashMap<>();
        base.mentions().forEach(mention -> mentions.put(mention.id(), mention));
        Map<Integer, ParsedStatements.Sentence> sentences = new java.util.HashMap<>();
        base.sentences().forEach(sentence -> sentences.put(sentence.index(), sentence));
        Map<Integer, List<ParsedStatements.Statement>> chosen = new java.util.TreeMap<>();
        Map<Integer, ParsedStatements.Section> sectionOf = new java.util.HashMap<>();
        int withoutSection = 0;
        for (ParsedStatements.Statement statement : base.statements()) {
            if (!request.scope().includes(statement) || !sentences.containsKey(statement.sentence())) {
                continue;
            }
            if (statement.derivation().origin() == ParsedStatements.Origin.VORSTUFE) {
                // Ohne den Abschnitt der Vorstufe (Analyse vor FL4) sähe das Modell nicht, woher ihre Beteiligten
                // stammen; solche Aussagen bleiben ungeprüft, bis die Seite neu analysiert ist
                if (statement.derivation().section() == null) {
                    withoutSection++;
                    continue;
                }
                sectionOf.merge(statement.sentence(), statement.derivation().section(), StatementAnalysis::wider);
            }
            chosen.computeIfAbsent(statement.sentence(), index -> new java.util.ArrayList<>()).add(statement);
        }
        List<StatementChecks.Group> groups = new java.util.ArrayList<>();
        List<List<ParsedStatements.Statement>> grouped = new java.util.ArrayList<>();
        List<String> sources = new java.util.ArrayList<>();
        chosen.forEach((index, statements) -> {
            String sentence = sentenceText(content, sentences.get(index));
            // ADR 0032, FL4: Mit einer Aussage der Vorstufe geht derselbe Abschnitt wie bei der Vorstufe mit
            List<String> context = sectionOf.containsKey(index)
                    ? lines(content, base.sentences(), sectionOf.get(index)) : List.of();
            groups.add(new StatementChecks.Group(sentence, context, statements.stream()
                    .map(statement -> StatementCheck.Item.of(statement, mentions)).toList()));
            grouped.add(statements);
            sources.add(StatementCheck.source(sentence, context));
        });
        StatementChecks.Costs run = groups.isEmpty()
                ? new StatementChecks.Costs(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                : null;
        List<Map<Integer, StatementCheck.Answer>> answers = List.of();
        if (!groups.isEmpty()) {
            StatementChecks.Result result = checks.check(key, analysis.inputSha256(), groups, request.parallel(),
                    cancelled);
            run = result.costs();
            answers = result.answers();
        }
        List<Corrections.Line> lines = new java.util.ArrayList<>();
        for (int g = 0; g < grouped.size(); g++) {
            for (Map.Entry<Integer, StatementCheck.Answer> entry : answers.get(g).entrySet()) {
                if (entry.getValue().correction() != null) {
                    lines.add(new Corrections.Line(grouped.get(g).get(entry.getKey() - 1),
                            entry.getValue().correction(), sources.get(g)));
                }
            }
        }
        Corrections.Merged merged = new Corrections.Merged(base, Set.of(), Map.of());
        int unparsed = 0;
        StatementAnalyzer parser = analyzer.get();
        if (!lines.isEmpty() && parser == null) {
            unparsed = lines.size(); // ohne Satzanalysator (Dienst) bleibt jede Korrektur ungeparst, also ungültig
        } else if (!lines.isEmpty()) {
            StringBuilder correctionText = new StringBuilder();
            int[] lineStarts = new int[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                lineStarts[i] = correctionText.length();
                correctionText.append(lines.get(i).correction()).append('\n');
            }
            String parsedId = analysis.documentId() + "-check";
            ParsedStatements parsed = parser.analyze(parsedId, correctionText.toString(), cancelled);
            merged = Corrections.merge(base, analysis.documentId(), content, lines, parsed, lineStarts, parsedId);
        }
        String version = checks.version();
        Map<String, ParsedStatements.Check> verdicts = new java.util.HashMap<>();
        for (int g = 0; g < grouped.size(); g++) {
            for (Map.Entry<Integer, StatementCheck.Answer> entry : answers.get(g).entrySet()) {
                ParsedStatements.Statement statement = grouped.get(g).get(entry.getKey() - 1);
                verdicts.put(statement.id(), new ParsedStatements.Check(entry.getValue().verdict(), version,
                        merged.corrected().contains(statement.id())));
            }
        }
        ParsedStatements checked = merged.result();
        checked = new ParsedStatements(checked.sentences(), checked.mentions(), checked.statements().stream()
                .map(statement -> verdicts.containsKey(statement.id())
                        ? statement.withCheck(verdicts.get(statement.id())) : statement).toList(),
                checked.attachments(), checked.components(), null);
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Prüfschritt abgebrochen");
        }
        if (!checks.released(key)) {
            // ADR 0028: Freigabe während des Laufs entzogen, kein Urteil und keine Korrektur in den Graph
            throw new CheckUnavailableException(CheckUnavailableException.Reason.NOT_RELEASED);
        }
        check(checked, content.length());
        store.replace(analysis.withResult(withConllu(key, checked)));
        int rejectedCorrections = unparsed + merged.rejected().values().stream().mapToInt(Integer::intValue).sum();
        costs.accept(new CheckCosts(run, merged.corrected().size(), rejectedCorrections, withoutSection));
        return new PageStatements(PageStatements.State.CURRENT, null, reviewed(analysis.withResult(checked)));
    }

    /**
     * Entfernt nach dem Entzug der KI-Freigabe (ADR 0028) aus den gespeicherten Analysen der Seiten ohne Freigabe die
     * Urteile und Korrekturen des Prüfschritts (ADR 0032, FL4), ohne neu zu rechnen und ohne die Analyse veralten zu
     * lassen: Die Stufe des Parsers war nie verändert, die Aussagen stehen danach genau wie vor dem Prüflauf da.
     * {@link #withdrawStage} tut das mit, wenn die Vorstufe ebenfalls zurückgeht.
     *
     * @return Zahl der geänderten Analysen
     */
    public int withdrawChecks(java.util.Collection<PageKey> pages, java.util.function.Predicate<PageKey> released) {
        int changed = 0;
        for (KnowledgeGraphStore each : stores()) {
            for (PageKey page : pages) {
                if (released.test(page)) {
                    continue;
                }
                Optional<PageStatementAnalysis> stored = each.find(page);
                if (stored.isEmpty()) {
                    continue;
                }
                ParsedStatements unchecked = withoutChecks(stored.get().result());
                if (unchecked != stored.get().result()) {
                    each.replace(stored.get().withResult(withConllu(each, page, unchecked)));
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Gibt jeder Aussage der Vorstufe den Abschnitt ihres Aufrufs mit (ADR 0032, FL4): Der Prüfschritt zeigt dem Modell
     * dieselben Zeilen als Zusammenhang, denn eine Vorstufen-Aussage darf Beteiligte aus Nachbarzeilen tragen.
     */
    static ParsedStatements withSections(ParsedStatements result, Map<Integer, ParsedStatements.Section> sections) {
        if (sections.isEmpty() || result.statements().stream()
                .noneMatch(statement -> statement.derivation().origin() == ParsedStatements.Origin.VORSTUFE)) {
            return result;
        }
        return new ParsedStatements(result.sentences(), result.mentions(), result.statements().stream()
                .map(statement -> statement.derivation().origin() == ParsedStatements.Origin.VORSTUFE
                        && sections.containsKey(statement.sentence())
                        ? statement.withDerivation(statement.derivation().withSection(
                        sections.get(statement.sentence()))) : statement).toList(),
                result.attachments(), result.components(), result.conllu());
    }

    /** Die Analyse mit der gespeicherten CoNLL-U der Seite, damit ein Ersetzen die Syntaxdetails behält. */
    private ParsedStatements withConllu(PageKey page, ParsedStatements result) {
        return withConllu(store, page, result);
    }

    private static ParsedStatements withConllu(KnowledgeGraphStore from, PageKey page, ParsedStatements result) {
        return result.conllu() != null ? result : result.withConllu(from.conllu(page).orElse(null));
    }

    /** Der größere von zwei Abschnitten desselben Satzes (sie sind gleich, solange eine Analyse sie setzte). */
    private static ParsedStatements.Section wider(ParsedStatements.Section a, ParsedStatements.Section b) {
        return new ParsedStatements.Section(Math.min(a.first(), b.first()), Math.max(a.last(), b.last()));
    }

    /** Die nicht leeren Sätze eines Abschnitts in Textreihenfolge, je eine Zeile, wie die Vorstufe sie sah. */
    private static List<String> lines(String text, List<ParsedStatements.Sentence> sentences,
                                      ParsedStatements.Section section) {
        List<String> lines = new java.util.ArrayList<>();
        sentences.stream().filter(sentence -> section.contains(sentence.index()))
                .sorted(java.util.Comparator.comparingInt(ParsedStatements.Sentence::index))
                .map(sentence -> sentenceText(text, sentence).replaceAll("\\s+", " ").strip())
                .filter(line -> !line.isEmpty()).forEach(lines::add);
        return List.copyOf(lines);
    }

    private static String sentenceText(String text, ParsedStatements.Sentence sentence) {
        int start = Math.min(Math.max(0, sentence.start()), text.length());
        int end = Math.min(Math.max(start, sentence.end()), text.length());
        return text.substring(start, end);
    }

    /** Warum eine gespeicherte Analyse veraltet ist, oder {@code null}, wenn sie aktuell ist. */
    String reason(PageStatementAnalysis stored, Optional<String> input) {
        if (input.isEmpty()) {
            return PageStatements.NO_TEXT;
        }
        if (!stored.inputSha256().equals(input.get())) {
            return PageStatements.TEXT_CHANGED;
        }
        StatementAnalyzer parser = analyzer(AnalyzerChoice.of(stored.analyzerId()));
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
        for (KnowledgeGraphStore each : stores()) {
            for (PageKey page : pages) {
                if (released.test(page)) {
                    continue;
                }
                Optional<PageStatementAnalysis> stored = each.find(page);
                if (stored.isEmpty()) {
                    continue;
                }
                ParsedStatements result = stored.get().result();
                // ADR 0032, FL4: Urteile und Korrekturen des Prüfschritts fallen mit der Freigabe, ohne die Analyse
                // veralten zu lassen; die Aussagen stehen danach genau wie vor dem Prüflauf da
                ParsedStatements unchecked = withoutChecks(result);
                ParsedStatements stripped = withoutStage(unchecked);
                if (stripped != unchecked) {
                    // Die ersetzten Original-Aussagen fehlen jetzt: veraltet, der nächste Lauf rechnet ohne Vorstufe
                    each.replace(stored.get().withResult(withConllu(each, page, stripped))
                            .withStage(PageStatementAnalysis.STAGE_WITHDRAWN));
                    changed++;
                } else if (unchecked != result || stored.get().stage() != null
                        && !PageStatementAnalysis.STAGE_WITHDRAWN.equals(stored.get().stage())) {
                    String stage = PageStatementAnalysis.STAGE_WITHDRAWN.equals(stored.get().stage())
                            ? stored.get().stage() : null; // keine Aussage der Vorstufe übrig, nichts fehlt
                    each.replace(stored.get().withResult(withConllu(each, page, unchecked)).withStage(stage));
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Die Analyse ohne Aussagen der Vorstufe und ohne die Erwähnungen, auf die nur sie zeigten; dieselbe Instanz, wenn
     * es keine gab.
     */
    static ParsedStatements withoutStage(ParsedStatements result) {
        Set<String> stage = new HashSet<>();
        result.statements().stream()
                .filter(statement -> statement.derivation().origin() == ParsedStatements.Origin.VORSTUFE)
                .forEach(statement -> stage.add(statement.id()));
        // eine Korrektur einer Aussage der Vorstufe fällt mit ihr
        return without(result, statement -> stage.contains(statement.id())
                || stage.contains(statement.derivation().corrects()));
    }

    /**
     * Die Analyse ohne Urteile und Korrekturen des Prüfschritts (ADR 0032, FL4): ohne {@link ParsedStatements.Check}
     * an den Aussagen und ohne Aussagen mit Herkunft {@link ParsedStatements.Origin#MODELL} samt den Erwähnungen, auf
     * die nur sie zeigten. Die Stufe des Parsers war nie verändert, also steht jede Aussage danach genau wie vor dem
     * Prüflauf da. Dieselbe Instanz, wenn es nichts zu entfernen gab.
     */
    public static ParsedStatements withoutChecks(ParsedStatements result) {
        boolean checked = result.statements().stream().anyMatch(statement -> statement.check() != null
                || statement.derivation().origin() == ParsedStatements.Origin.MODELL);
        if (!checked) {
            return result;
        }
        ParsedStatements stripped = without(result,
                statement -> statement.derivation().origin() == ParsedStatements.Origin.MODELL);
        return new ParsedStatements(stripped.sentences(), stripped.mentions(), stripped.statements().stream()
                .map(statement -> statement.check() == null ? statement : statement.withCheck(null)).toList(),
                stripped.attachments(), stripped.components(), stripped.conllu());
    }

    /** Die Analyse ohne die Aussagen, die {@code drop} annimmt, und ohne die Erwähnungen, auf die nur sie zeigten. */
    private static ParsedStatements without(ParsedStatements result,
                                            java.util.function.Predicate<ParsedStatements.Statement> drop) {
        List<ParsedStatements.Statement> kept = result.statements().stream().filter(drop.negate()).toList();
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
                .filter(drop)
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
     * Ein Prüflauf (ADR 0032, FL4).
     *
     * @param scope    welche Aussagen das Modell sieht
     * @param parallel so viele Sätze einer Seite höchstens zugleich beim Modell
     */
    public record CheckRequest(CheckScope scope, int parallel) {

        public CheckRequest {
            Objects.requireNonNull(scope, "scope");
            if (parallel < 1) {
                throw new IllegalArgumentException("parallel muss mindestens 1 sein");
            }
        }
    }

    /**
     * Zahlen eines Prüflaufs, ohne Inhalte.
     *
     * @param run                 Zahlen des Modellteils
     * @param corrections         übernommene Korrekturen
     * @param rejectedCorrections Korrekturen, die nach dem Wortabgleich nicht als Aussage taugten (Verb, Verneinung,
     *                            Handelnder, Beteiligte) oder ohne Satzanalysator ungeparst blieben
     * @param withoutSection      gewählte Aussagen der Vorstufe ohne gespeicherten Abschnitt (Analyse vor FL4),
     *                            ungeprüft geblieben
     */
    public record CheckCosts(StatementChecks.Costs run, int corrections, int rejectedCorrections, int withoutSection) {
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
