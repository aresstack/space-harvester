package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.ai.ModelRetry;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Der zweite Aussagenanalysator (Fahrplan Analyse-Reiter, Paket A2): Das Chat-Modell des eingestellten KI-Dienstes
 * nennt je Satz Tätigkeit, Handelnden, Betroffenes und Empfänger direkt; dahinter prüft der Wortabgleich, dass jeder
 * Beteiligte wörtlich im Satz steht. Austauschbar neben dem MaltParser, der Vorgabe bleibt; gewählt wird je Lauf.
 *
 * <ul>
 *     <li>Sätze: jede Zeile des Lesetexts ist eine Blockgrenze, darin die Satzgrenzen des Core
 *     ({@link SentenceSegmenter}); je Aufruf höchstens {@value #MAX_LINES} Sätze als {@code Nummer Satz}, Nummern ab 1.
 *     Der Seitentext geht nur als Benutzernachricht hinaus und ist Material, keine Anweisung.</li>
 *     <li>Antwort: streng gelesen ({@link ModelAnswer}). Zeilen außerhalb des Formats oder mit fremder Satznummer werden
 *     verworfen und gezählt; ein Satz ohne Antwortzeile hat keine eindeutige Aussage.</li>
 *     <li>Wortabgleich: Jeder Beteiligte muss wörtlich (Groß- und Kleinschreibung egal, an Wortgrenzen) im Satz stehen,
 *     sonst wird die Aussage verworfen. „§verb“ (ein Teilsatz als Beteiligter) gilt nur, wenn derselbe Satz eine
 *     Aussage mit diesem Verb hat. Betroffenes und Empfänger hinter einer Präposition („für den Antragsteller“) sind
 *     keine Objekte: Diese Beteiligten fallen weg, die Aussage bleibt.</li>
 *     <li>Herkunft: Jeder Beteiligte trägt {@link ParsedStatements.Basis#MODEL}; die Analyse trägt Kennung
 *     {@value #ID} und als Version Modell und Anweisung ({@link #version()}). Stufe PLAUSIBLE, solange der Wortabgleich
 *     bestand; der Prüfschritt kann sie weiter senken.</li>
 * </ul>
 *
 * <p><b>Version 1</b> der Anweisung ({@code anweisung-v1.txt}) ist auf dem Entwicklungssatz des Prüfstands mit
 * gemma4:12b erprobt; jede Änderung an Anweisung, Eingabe oder Format ist eine neue {@link #PROMPT_VERSION} und braucht
 * eine neue Messung. Threadsicher.
 */
public final class ModelStatementAnalyzer implements StatementAnalyzer {

    /** Kennung des Verfahrens. */
    public static final String ID = "language-model";
    /** Kennung des Analyseschritts beim Chat-Modell ({@code ChatStepModel}). */
    public static final String STEP = "statement-model";
    /** Version von Anweisung, Eingabe und Antwortformat; Teil der Version der Analyse. */
    public static final String PROMPT_VERSION = "1";
    /** Höchstens so viele Sätze je Modellaufruf. */
    static final int MAX_LINES = 40;
    /** Längster Satz, der an das Modell geht; längere Zeilen (Tabellen, Daten) gehen nicht hinaus. */
    static final int MAX_SENTENCE_CHARS = 600;

    /**
     * Stand des Wortabgleichs (Pronomen, Präpositionen, Teilsätze), unabhängig von der Anweisung: eine Änderung macht
     * gespeicherte Analysen veraltet, ohne eine neue Messung der Anweisung zu verlangen.
     */
    static final String MATCHING_VERSION = "2";
    private static final String INSTRUCTIONS = resource("anweisung-v1.txt");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}-]*");
    private static final Set<String> PREPOSITIONS = Set.of("an", "am", "auf", "aus", "bei", "beim", "bis", "durch",
            "für", "gegen", "hinter", "in", "im", "ins", "mit", "nach", "neben", "ohne", "seit", "über", "um", "unter",
            "von", "vom", "vor", "während", "wegen", "zu", "zum", "zur", "zwischen", "innerhalb", "außerhalb");
    private static final Set<String> DETERMINERS = Set.of("der", "die", "das", "den", "dem", "des", "ein", "eine",
            "einen", "einem", "einer", "eines", "kein", "keine", "keinen", "keinem", "keiner", "keines", "jeder",
            "jede", "jedes", "jeden", "jedem", "alle", "allen", "diese", "dieser", "dieses", "diesen", "diesem",
            "sein", "seine", "seinen", "seinem", "seiner", "ihr", "ihre", "ihren", "ihrem", "ihrer", "unser",
            "unsere", "unseren", "unserem", "unserer");
    private static final Set<String> PRONOUNS = Set.of("er", "sie", "es", "ihn", "ihm", "ihnen", "wer", "was", "wen",
            "wem", "man", "dies", "diese", "dieser", "dieses", "der", "die", "das", "ich", "du", "wir", "ihr", "mich",
            "dich", "mir", "dir", "uns", "euch", "sich", "jemand", "niemand");
    /** Personalpronomen: Sie beenden die Suche nach einer Präposition vor einem Beteiligten. */
    private static final Set<String> PERSONAL_PRONOUNS = Set.of("er", "sie", "es", "ihn", "ihm", "ihnen", "man",
            "wer", "was", "wen", "wem", "ich", "du", "wir", "uns", "euch", "mich", "dich", "mir", "dir", "sich");
    /** Trennbare Verbzusätze, die vor dem Stamm wegfallen, wenn das Prädikat im Satz gesucht wird. */
    private static final List<String> PARTICLES = List.of("zurück", "weiter", "fest", "frei", "nach", "vor", "ein",
            "aus", "auf", "an", "ab", "mit", "zu", "bei", "dar", "her", "hin", "los", "um", "durch", "über", "unter",
            "wieder", "weg");

    private final StepModel model;
    private final SentenceSegmenter sentences;

    /**
     * @param model     Chat-Modell des Schritts {@value #STEP}; Temperatur 0, Denken aus
     * @param sentences Satzgrenzen des Core, produktiv {@code OpenNlpSentenceSegmenter.german(…)}
     */
    public ModelStatementAnalyzer(StepModel model, SentenceSegmenter sentences) {
        this.model = Objects.requireNonNull(model, "model");
        this.sentences = Objects.requireNonNull(sentences, "sentences");
    }

    /** Die Anweisung der aktuellen {@link #PROMPT_VERSION}, unverändert. */
    public static String instructions() {
        return INSTRUCTIONS;
    }

    @Override
    public String id() {
        return ID;
    }

    /** Modell samt Fingerabdruck des Dienstes und Version der Anweisung; ändert sie sich, veralten die Analysen. */
    @Override
    public String version() {
        return model.version() + "|prompt-" + PROMPT_VERSION + "|abgleich-" + MATCHING_VERSION;
    }

    @Override
    public boolean remote() {
        return true;
    }

    @Override
    public boolean ready() {
        return model.available();
    }

    @Override
    public ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled) {
        return analyze(documentId, text, cancelled, costs -> {
        });
    }

    /**
     * @throws AnalyzerUnavailableException ohne eingestelltes Modell ({@link AnalyzerUnavailableException.Reason#NO_AI_MODEL})
     * @throws CancellationException        nach einem Abbruch
     * @throws RuntimeException             wenn der Dienst scheitert; die Meldung nennt nie Text oder Antwort
     */
    @Override
    public ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled,
                                    Consumer<Costs> costs) {
        return analyze(documentId, text, cancelled, () -> true, costs);
    }

    /**
     * Wie oben; {@code released} wird vor jedem Modellaufruf geprüft (ADR 0028).
     *
     * @throws AnalyzerUnavailableException mit {@link AnalyzerUnavailableException.Reason#NOT_RELEASED}, sobald die
     *                                      Freigabe vor einem Aufruf fehlt
     */
    @Override
    public ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled,
                                    BooleanSupplier released, Consumer<Costs> costs) {
        Objects.requireNonNull(released, "released");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(costs, "costs");
        if (!model.available()) {
            throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NO_AI_MODEL,
                    "Kein Chat-Modell eingestellt (Einstellungen › Modelle)", null);
        }
        List<ParsedStatements.Sentence> found = split(text);
        List<ParsedStatements.Sentence> asked = found.stream()
                .filter(sentence -> sentence.end() - sentence.start() <= MAX_SENTENCE_CHARS).toList();
        Builder builder = new Builder(documentId, text);
        int calls = 0;
        int rejectedLines = 0;
        for (int from = 0; from < asked.size(); from += MAX_LINES) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Analyse abgebrochen");
            }
            if (!released.getAsBoolean()) {
                throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NOT_RELEASED,
                        "Seite nicht für den KI-Dienst freigegeben", null);
            }
            List<ParsedStatements.Sentence> chunk = asked.subList(from, Math.min(asked.size(), from + MAX_LINES));
            String response = model.completeRetrying(INSTRUCTIONS, input(text, chunk),
                    ModelRetry.logging(cancelled));
            calls++;
            ModelAnswer.Parsed parsed = ModelAnswer.parse(chunk.size(), response);
            rejectedLines += parsed.rejected();
            for (int i = 0; i < chunk.size(); i++) {
                builder.sentence(chunk.get(i), parsed.items().getOrDefault(i + 1, List.of()));
            }
        }
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Analyse abgebrochen");
        }
        costs.accept(new Costs(asked.size(), calls, rejectedLines, builder.rejected, builder.empty));
        return new ParsedStatements(found, builder.mentions, builder.statements, List.of(),
                List.of("analyzer=" + ID, "model=" + model.version(), "prompt=" + PROMPT_VERSION), null);
    }

    /** Die Eingabe: je Satz {@code Nummer Satz}, Leerraum zu einem Leerzeichen, Nummern ab 1. */
    static String input(String text, List<ParsedStatements.Sentence> chunk) {
        StringBuilder input = new StringBuilder();
        int number = 1;
        for (ParsedStatements.Sentence sentence : chunk) {
            input.append(number++).append(' ')
                    .append(text.substring(sentence.start(), sentence.end()).replaceAll("\\s+", " ").strip())
                    .append('\n');
        }
        return input.toString();
    }

    /** Sätze mit Spannen: jede Zeile eine Blockgrenze, darin die Satzgrenzen; Zeilen ohne Buchstaben fallen weg. */
    List<ParsedStatements.Sentence> split(String text) {
        List<ParsedStatements.Sentence> result = new ArrayList<>();
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd);
            if (line.chars().anyMatch(Character::isLetter)) {
                int cursor = 0;
                for (String sentence : sentences.sentences(line)) {
                    int at = line.indexOf(sentence, cursor);
                    if (at < 0) {
                        // Satzgrenzen, die den Wortlaut ändern: der Rest der Zeile als ein Satz
                        add(result, text, lineStart + cursor, lineEnd);
                        cursor = line.length();
                        break;
                    }
                    add(result, text, lineStart + at, lineStart + at + sentence.length());
                    cursor = at + sentence.length();
                }
            }
            lineStart = lineEnd + 1;
        }
        return result;
    }

    private static void add(List<ParsedStatements.Sentence> result, String text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end > start && text.substring(start, end).chars().anyMatch(Character::isLetter)) {
            result.add(new ParsedStatements.Sentence(result.size(), start, end));
        }
    }

    /** Baut Erwähnungen und Aussagen satzweise; zählt verworfene Aussagen und Sätze ohne Aussage. */
    private static final class Builder {

        private final String documentId;
        private final String text;
        private final List<ParsedStatements.Mention> mentions = new ArrayList<>();
        private final Map<String, ParsedStatements.Mention> byId = new LinkedHashMap<>();
        private final List<ParsedStatements.Statement> statements = new ArrayList<>();
        private int paragraph;
        private int lastLineStart = -1;
        private int rejected;
        private int empty;

        Builder(String documentId, String text) {
            this.documentId = documentId;
            this.text = text;
        }

        void sentence(ParsedStatements.Sentence sentence, List<ModelAnswer.Item> items) {
            String content = text.substring(sentence.start(), sentence.end());
            int paragraphOf = paragraph(sentence.start());
            // Erst prüfen, dann bauen: ein Teilsatz als Beteiligter zeigt auf eine gültige Aussage desselben Satzes
            List<Accepted> accepted = new ArrayList<>();
            for (ModelAnswer.Item item : items) {
                Accepted candidate = accept(sentence, content, item);
                if (candidate == null) {
                    rejected++;
                } else {
                    accepted.add(candidate);
                }
            }
            // Bis nichts mehr wegfällt: fällt das Ziel eines Teilsatzes weg, fällt auch die Aussage, die darauf zeigt
            // (entscheiden → §prüfen → §fördern ohne fördern); geprüft wird gegen dieselbe Liste, aus der gebaut wird
            List<Accepted> kept = new ArrayList<>(accepted);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (java.util.Iterator<Accepted> it = kept.iterator(); it.hasNext(); ) {
                    Accepted candidate = it.next();
                    if (!candidate.clauses().stream().allMatch(clause -> clauseTarget(kept, candidate, clause) != null)) {
                        it.remove();
                        rejected++;
                        changed = true;
                    }
                }
            }
            if (kept.isEmpty()) {
                empty++;
                return;
            }
            int number = 0;
            Map<Accepted, String> ids = new java.util.IdentityHashMap<>();
            for (Accepted candidate : kept) {
                ids.put(candidate, documentId + "#s" + sentence.index() + ".v" + number++);
            }
            for (Accepted candidate : kept) {
                List<ParsedStatements.Argument> arguments = new ArrayList<>();
                for (Participant participant : candidate.participants()) {
                    String mentionId;
                    if (participant.clause() != null) {
                        Accepted target = clauseTarget(kept, candidate, participant.clause());
                        if (target == null) {
                            throw new IllegalStateException("Teilsatz ohne Ziel nach der Prüfung");
                        }
                        mentionId = mention(sentence, target.predicateStart(), target.predicateEnd(),
                                text.substring(target.predicateStart(), target.predicateEnd()), target.verb(),
                                ParsedStatements.MentionKind.CLAUSE, ".k" + ids.get(target).substring(
                                ids.get(target).lastIndexOf('.') + 1));
                    } else {
                        mentionId = mention(sentence, participant.start(), participant.end(),
                                text.substring(participant.start(), participant.end()), null,
                                kind(text.substring(participant.start(), participant.end())), null);
                    }
                    arguments.add(new ParsedStatements.Argument(participant.role(), mentionId,
                            "model:" + participant.field(), null, participant.role()
                            == ParsedStatements.Role.RECIPIENT ? ParsedStatements.Priority.SUPPLEMENTARY
                            : ParsedStatements.Priority.CORE, ParsedStatements.Basis.MODEL, true));
                }
                Set<String> findings = candidate.state() == null ? Set.of() : Set.of(candidate.state());
                boolean hasActor = arguments.stream().anyMatch(a -> a.role() == ParsedStatements.Role.ACTOR);
                boolean hasAffected = arguments.stream()
                        .anyMatch(a -> a.role() == ParsedStatements.Role.AFFECTED_OBJECT);
                ModelAnswer.Item item = candidate.item();
                statements.add(new ParsedStatements.Statement(ids.get(candidate), sentence.index(),
                        candidate.predicateStart(), candidate.predicateEnd(),
                        text.substring(candidate.predicateStart(), candidate.predicateEnd()), candidate.verb(),
                        ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN,
                        candidate.state() == null && hasActor && hasAffected, arguments, findings, paragraphOf,
                        ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                        new ParsedStatements.Features(item.negation(), item.modality(), item.temporality()),
                        ParsedStatements.Derivation.ORIGINAL));
            }
        }

        /** Absatz = Zahl der Zeilenumbrüche vor der Stelle, fortlaufend gezählt. */
        private int paragraph(int start) {
            if (lastLineStart < 0) {
                lastLineStart = 0;
            }
            for (int i = lastLineStart; i < start; i++) {
                if (text.charAt(i) == '\n') {
                    paragraph++;
                }
            }
            lastLineStart = Math.max(lastLineStart, start);
            return paragraph;
        }

        private String mention(ParsedStatements.Sentence sentence, int start, int end, String wording, String lemma,
                               ParsedStatements.MentionKind kind, String suffix) {
            String id = documentId + "#s" + sentence.index() + (suffix != null ? suffix : ".c" + start);
            if (!byId.containsKey(id)) {
                String[] words = wording.strip().split("\\s+");
                ParsedStatements.Mention mention = new ParsedStatements.Mention(id, sentence.index(), start, end,
                        wording, words[words.length - 1], lemma, kind,
                        kind == ParsedStatements.MentionKind.PRONOUN, Set.of());
                byId.put(id, mention);
                mentions.add(mention);
            }
            return id;
        }
    }

    /** Ein Beteiligter nach dem Wortabgleich: Spanne im Text oder ein Teilsatz („§verb“). */
    private record Participant(ParsedStatements.Role role, String field, int start, int end, String clause) {
    }

    /** Eine Aussage nach dem Wortabgleich. */
    private record Accepted(ModelAnswer.Item item, String verb, String state, int predicateStart, int predicateEnd,
                            List<Participant> participants) {

        List<String> clauses() {
            return participants.stream().map(Participant::clause).filter(Objects::nonNull).toList();
        }
    }

    /**
     * Wortabgleich einer Aussage im Satz; {@code null}, wenn ein Beteiligter nicht wörtlich im Satz steht. Betroffenes
     * und Empfänger hinter einer Präposition fallen weg.
     */
    private static Accepted accept(ParsedStatements.Sentence sentence, String content, ModelAnswer.Item item) {
        String verb = item.verb().strip();
        if (verb.chars().noneMatch(Character::isLetter)) {
            return null;
        }
        String state = state(verb);
        List<Participant> participants = new ArrayList<>();
        ParsedStatements.Role actorRole = state != null ? ParsedStatements.Role.BEARER : ParsedStatements.Role.ACTOR;
        if (!participants(sentence, content, item.actors(), actorRole, "handelnder", false, participants)
                || !participants(sentence, content, item.affected(), ParsedStatements.Role.AFFECTED_OBJECT,
                "betroffenes", true, participants)
                || !participants(sentence, content, item.recipients(), ParsedStatements.Role.RECIPIENT, "empfaenger",
                true, participants)) {
            return null;
        }
        int[] predicate = predicate(content, verb, state != null);
        int start = predicate == null ? sentence.start() : sentence.start() + predicate[0];
        int end = predicate == null ? sentence.end() : sentence.start() + predicate[1];
        return new Accepted(item, state != null && Character.isUpperCase(verb.charAt(0)) ? verb
                : verb.toLowerCase(Locale.ROOT), state, start, end, List.copyOf(participants));
    }

    private static boolean participants(ParsedStatements.Sentence sentence, String content, List<String> values,
                                        ParsedStatements.Role role, String field, boolean object,
                                        List<Participant> into) {
        for (String value : values) {
            if (value.startsWith("§")) {
                String clause = value.substring(1).strip().toLowerCase(Locale.ROOT);
                if (clause.isEmpty()) {
                    return false;
                }
                into.add(new Participant(role, field, -1, -1, clause));
                continue;
            }
            int[] span = find(content, value);
            if (span == null) {
                return false;
            }
            if (object && behindPreposition(content, span[0])) {
                continue; // „für den Antragsteller“: kein Objekt, der Beteiligte fällt weg
            }
            into.add(new Participant(role, field, sentence.start() + span[0], sentence.start() + span[1], null));
        }
        return true;
    }

    /** Die Aussage desselben Satzes, auf die ein „§verb“ zeigt; nie die Aussage selbst. */
    private static Accepted clauseTarget(List<Accepted> candidates, Accepted self, String clause) {
        for (Accepted other : candidates) {
            if (other != self && (sameStem(clause, other.verb().toLowerCase(Locale.ROOT))
                    || sameStem(other.verb().toLowerCase(Locale.ROOT), clause))) {
                return other;
            }
        }
        return null;
    }

    /**
     * Zustandsaussage: ein Nomen („Ansprechpartner“) ist {@code STATE_ROLE_NOUN}, ein Wort, das nicht wie ein
     * Infinitiv endet („zuständig“), {@code STATE_PROPERTY}; sonst {@code null} (Handlung).
     */
    static String state(String verb) {
        if (Character.isUpperCase(verb.charAt(0))) {
            return ParsedStatements.Statement.STATE_ROLE_NOUN;
        }
        String lower = verb.toLowerCase(Locale.ROOT);
        return lower.endsWith("n") ? null : ParsedStatements.Statement.STATE_PROPERTY;
    }

    /** Wörtlich im Satz, an Wortgrenzen, Groß- und Kleinschreibung egal; ein führender Artikel darf fehlen. */
    static int[] find(String content, String value) {
        int[] span = exact(content, value);
        if (span != null) {
            return span;
        }
        String[] words = value.strip().split("\\s+", 2);
        if (words.length == 2 && DETERMINERS.contains(words[0].toLowerCase(Locale.ROOT))) {
            return exact(content, words[1]);
        }
        return null;
    }

    private static int[] exact(String content, String value) {
        String needle = value.strip();
        if (needle.isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(content);
        return matcher.find() ? new int[] {matcher.start(), matcher.end()} : null;
    }

    /**
     * Ob vor der Stelle eine Präposition steht, höchstens über Artikel und kleingeschriebene Beiwörter hinweg („für
     * den neuen Antragsteller“, „beim Antragsteller“).
     */
    static boolean behindPreposition(String content, int start) {
        List<String> before = new ArrayList<>();
        Matcher matcher = WORD.matcher(content.substring(0, start));
        while (matcher.find()) {
            before.add(matcher.group());
        }
        // Nur die Form einer Nominalgruppe von rechts: [Beiwort]* [Artikel]? Präposition. Verben (-t), Pronomen
        // und Großgeschriebenes beenden die Suche („Für sie erstellt er Bescheide“ bleibt ein Objekt).
        int i = before.size() - 1;
        int steps = 0;
        while (i >= 0 && steps < 4 && adjectiveLike(before.get(i))) {
            i--;
            steps++;
        }
        if (i >= 0 && DETERMINERS.contains(before.get(i).toLowerCase(Locale.ROOT))
                && !PREPOSITIONS.contains(before.get(i).toLowerCase(Locale.ROOT))) {
            i--;
        }
        return i >= 0 && PREPOSITIONS.contains(before.get(i).toLowerCase(Locale.ROOT));
    }

    /** Kleingeschriebenes Beiwort mit Beugungsendung, kein Pronomen, Artikel oder Präposition. */
    private static boolean adjectiveLike(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return Character.isLowerCase(word.charAt(0)) && !PRONOUNS.contains(lower) && !PERSONAL_PRONOUNS.contains(lower)
                && !PREPOSITIONS.contains(lower) && !DETERMINERS.contains(lower)
                && (lower.endsWith("e") || lower.endsWith("en") || lower.endsWith("em") || lower.endsWith("er")
                || lower.endsWith("es"));
    }

    /** Art einer Erwähnung nach ihrem Wortlaut. */
    private static ParsedStatements.MentionKind kind(String wording) {
        String strip = wording.strip();
        if (strip.startsWith("Frau ") || strip.startsWith("Herr ")) {
            return ParsedStatements.MentionKind.PROPER_NOUN;
        }
        // auch am Satzanfang großgeschrieben („Sie prüft Anträge.“): ein Pronomen bleibt ein Pronomen
        if (PRONOUNS.contains(strip.toLowerCase(Locale.ROOT))) {
            return ParsedStatements.MentionKind.PRONOUN;
        }
        return Character.isUpperCase(strip.charAt(0)) ? ParsedStatements.MentionKind.NOUN
                : ParsedStatements.MentionKind.OTHER;
    }

    /**
     * Die Stelle des Prädikats im Satz: ein Wort mit dem Stamm der Grundform (auch ohne Verbzusatz, Umlaute
     * gleichgesetzt); {@code null}, wenn keins passt (starke Formen wie „gab … zurück“), dann gilt der ganze Satz.
     */
    static int[] predicate(String content, String verb, boolean state) {
        String lemma = fold(verb.toLowerCase(Locale.ROOT));
        List<String> stems = new ArrayList<>();
        String stem = state ? lemma : stem(lemma);
        stems.add(stem);
        if (!state) {
            for (String particle : PARTICLES) {
                if (lemma.startsWith(particle) && lemma.length() - particle.length() >= 4) {
                    stems.add(stem(lemma.substring(particle.length())));
                    break;
                }
            }
        }
        Matcher matcher = WORD.matcher(content);
        while (matcher.find()) {
            String word = fold(matcher.group().toLowerCase(Locale.ROOT));
            for (String candidate : stems) {
                if (candidate.length() >= 3 && (word.startsWith(candidate) || word.contains("ge" + candidate)
                        || word.contains("zu" + candidate))) {
                    return new int[] {matcher.start(), matcher.end()};
                }
            }
        }
        return null;
    }

    private static String stem(String lemma) {
        if (lemma.endsWith("ern") || lemma.endsWith("eln")) {
            return lemma.substring(0, lemma.length() - 1);
        }
        if (lemma.endsWith("en")) {
            return lemma.substring(0, lemma.length() - 2);
        }
        return lemma.endsWith("n") ? lemma.substring(0, lemma.length() - 1) : lemma;
    }

    private static String fold(String word) {
        return word.replace('ä', 'a').replace('ö', 'o').replace('ü', 'u');
    }

    /** Gleiche Grundform oder gleicher Stamm, wie im Abgleich des Prüfstands. */
    static boolean sameStem(String expected, String lemma) {
        if (expected.equals(lemma)) {
            return true;
        }
        String stem = stem(expected);
        return stem.length() >= 3 && lemma.startsWith(stem) && lemma.length() - stem.length() <= 2;
    }

    private static String resource(String name) {
        try (InputStream in = ModelStatementAnalyzer.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Anweisung fehlt: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
