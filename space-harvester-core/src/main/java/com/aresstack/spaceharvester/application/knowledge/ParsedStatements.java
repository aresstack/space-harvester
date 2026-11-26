package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ergebnis einer Satzanalyse im Core-Modell (#499), unabhängig vom Parser. Rohstruktur ({@link #conllu}) und fachliche
 * Ableitungen (Erwähnungen, Aussagen, nominale Bezüge, Kernsicht) bleiben getrennt; die Ableitungen verweisen über
 * IDs aufeinander, nie auf Entitäten.
 *
 * @param sentences   Sätze mit Spanne im Text, in Textreihenfolge
 * @param mentions    Erwähnungen in Textreihenfolge
 * @param statements  Aussagen in Textreihenfolge
 * @param attachments nominale Bezüge
 * @param components  Werkzeuge und Modelle je Schicht, z. B. {@code parser=maltparser-1.9.2 model=…@sha256:…}
 * @param conllu      vollständige UD-Analyse als CoNLL-U (Syntaxdetails, Diagnose) oder {@code null}, wenn sie nicht
 *                    geladen wurde
 */
public record ParsedStatements(List<Sentence> sentences, List<Mention> mentions, List<Statement> statements,
                               List<Attachment> attachments, List<String> components, String conllu) {

    public ParsedStatements {
        sentences = List.copyOf(sentences);
        mentions = List.copyOf(mentions);
        statements = List.copyOf(statements);
        attachments = List.copyOf(attachments);
        components = List.copyOf(components);
    }

    /** Dieselbe Analyse ohne CoNLL-U, etwa für den Speicher des Graphen. */
    public ParsedStatements withoutConllu() {
        return conllu == null ? this : new ParsedStatements(sentences, mentions, statements, attachments, components,
                null);
    }

    public ParsedStatements withConllu(String raw) {
        return new ParsedStatements(sentences, mentions, statements, attachments, components, raw);
    }

    /**
     * Ein Satz.
     *
     * @param index Satzindex ab 0
     * @param start Zeichenposition im Text (inklusive)
     * @param end   Zeichenposition im Text (exklusive)
     */
    public record Sentence(int index, int start, int end) {
    }

    /**
     * Eine Erwähnung: eine Nominalgruppe oder ein Pronomen an genau einer Textstelle.
     *
     * @param id         {@code quelle/seite@fassung#s<Satz>.t<Kopf>}
     * @param sentence   Satzindex
     * @param start      Zeichenposition im Text (inklusive)
     * @param end        Zeichenposition im Text (exklusive)
     * @param text       Wortlaut der Gruppe
     * @param headForm   Form des Kopfs
     * @param lemma      Grundform des Kopfs oder {@code null}
     * @param kind       Art nach der Wortart des Kopfs
     * @param unresolved Pronomen mit offenem Bezug; wird nie geraten
     * @param cases      mögliche Kasus ({@code Nom}, {@code Acc}, {@code Dat}, {@code Gen}); leer = unbekannt
     */
    public record Mention(String id, int sentence, int start, int end, String text, String headForm, String lemma,
                          MentionKind kind, boolean unresolved, Set<String> cases) {

        public Mention {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(kind, "kind");
            cases = Set.copyOf(cases);
        }

        /** Schlüssel für lexikalische Vergleiche: Grundform, sonst Kopfform. Nie eine Identität. */
        public String key() {
            return lemma != null && !lemma.isBlank() ? lemma : headForm;
        }
    }

    /** Art einer Erwähnung. */
    public enum MentionKind {
        NOUN,
        PROPER_NOUN,
        PRONOUN,
        OTHER,
        /**
         * Ein Teilsatz als Beteiligter (ADR 0032): verweist auf die Aussage des Teilsatzes (gleicher Satz, Kopf =
         * Prädikat), ist nie ein Begriff und wird in Begriffen, Themen und Graphknoten nicht gezählt.
         */
        CLAUSE
    }

    /**
     * Eine Aussage: ein Prädikat in einem Satz mit seinen Beteiligten.
     *
     * @param id             {@code quelle/seite@fassung#s<Satz>.v<Verb>}
     * @param sentence       Satzindex
     * @param start          Beginn des Prädikats im Text (erstes Prädikatsteil)
     * @param end            Ende des Prädikats im Text (letztes Prädikatsteil)
     * @param predicateText  Prädikat im Wortlaut ({@code ruft … an})
     * @param predicateLemma Grundform ({@code anrufen}) oder {@code null}
     * @param voice          Aktiv oder Passiv
     * @param clause         Haupt-, koordinierter oder Nebensatz
     * @param core           strukturell vollständige Aussage der priorisierten Sicht (aktiv, kein Nebensatz, Subjekt
     *                       und Objekt aus eigenen, widerspruchsfreien Parserkanten); keine inhaltliche Relevanz
     * @param arguments      Beteiligte, Kern zuerst
     * @param findings       erkannte, aber nicht aufgelöste Besonderheiten ({@code PASSIVE}, {@code UNRESOLVED_PRONOUN},
     *                       {@code TIME_FROM_NOMINAL} …)
     * @param paragraph      Absatz (Block der Textprojektion, gezählt an {@code \n}) ab 0: Gruppierung neben
     *                       {@code sentence}; {@value #UNKNOWN_PARAGRAPH}, wenn unbekannt
     * @param quality        Extraktionsqualität: wie gut die Aussage maschinell abgeleitet ist, nie ob sie stimmt
     * @param qualityReasons Gründe einer Abstufung ({@code HEURISTIC_PARTICIPANT}, {@code NO_PARTICIPANT} …), leer bei
     *                       {@link Quality#PLAUSIBLE}
     * @param review         fachliche Prüfung durch einen Menschen, unabhängig von {@code quality}; gespeicherte
     *                       Analysen tragen {@code UNREVIEWED}, die Prüfung von Hand legen die Lesewege darüber
     *                       ({@link StatementReviews})
     */
    /** Verneinung einer Aussage; {@link #UNKNOWN} bei Analysen ohne Merkmale. */
    public enum Negation {
        UNKNOWN,
        AFFIRMED,
        NEGATED
    }

    /** Verbindlichkeit nach dem Modalverb (ADR 0032); {@link #NONE} ohne Modalverb, {@link #UNKNOWN} nicht bestimmt. */
    public enum Modality {
        UNKNOWN,
        NONE,
        MUSS,
        SOLL,
        KANN,
        DARF
    }

    /** Zeitbezug einer Aussage aus Grammatik und Wörtern ihres Satzes (ADR 0032); nie aus dem heutigen Datum. */
    public enum Temporality {
        UNKNOWN,
        GILT,
        GEPLANT,
        FRUEHER,
        VERWORFEN
    }

    /**
     * Merkmale einer Aussage (ADR 0032, FL2).
     *
     * @param negation    verneint oder nicht
     * @param modality    Verbindlichkeit
     * @param temporality Zeitbezug
     */
    public record Features(Negation negation, Modality modality, Temporality temporality, Article article) {

        /** Merkmale nicht bestimmt (ältere Analysen, andere Analysatoren). */
        public static final Features UNKNOWN = new Features(Negation.UNKNOWN, Modality.UNKNOWN, Temporality.UNKNOWN);

        /**
         * @param article Begleiter des Rollennomens einer Zustandsaussage (ADR 0032); {@code null} bei Handlungen,
         *                Eigenschaften und Analysen vor rules-13
         */
        public Features {
            Objects.requireNonNull(negation, "negation");
            Objects.requireNonNull(modality, "modality");
            Objects.requireNonNull(temporality, "temporality");
        }

        /** Ohne Artikel. */
        public Features(Negation negation, Modality modality, Temporality temporality) {
            this(negation, modality, temporality, null);
        }

        /** Ob die Merkmale bestimmt wurden. */
        public boolean known() {
            return !equals(UNKNOWN);
        }
    }

    /**
     * Begleiter des Rollennomens einer Zustandsaussage („ist ein/der/kein Ansprechpartner“, ADR 0032): ein neutrales
     * Merkmal ohne Deutung. {@link #KEIN} ändert nichts an der Verneinung.
     */
    public enum Article {
        /** ein, eine, einen, einem, einer, eines. */
        UNBESTIMMT,
        /** der, die, das, den, dem, des. */
        BESTIMMT,
        /** Kein Begleiter, auch Mehrzahl ohne Artikel. */
        OHNE,
        /** kein, keine, keinen, keinem, keiner, keines. */
        KEIN,
        /** Jeder andere Begleiter (unser, ihr, dieser, jeder …). */
        SONSTIGER
    }

    /** Herkunft einer Aussage (ADR 0032, FL3). */
    public enum Origin {
        /** Aus dem Originalsatz. */
        ORIGINAL,
        /** Aus einem vereinfachten Satz der Vorstufe „Sätze vereinfachen“. */
        VORSTUFE,
        /**
         * Aus der Korrektur des Prüfschritts (ADR 0032, FL4): ein einfacher Hauptsatz des Chat-Modells, nur aus Wörtern
         * des Originalsatzes, geparst wie eine Zeile der Vorstufe; zeigt auf die Aussage, die er korrigiert.
         */
        MODELL,
        /**
         * Aus einer KI-Hypothese der Programmanalyse (ADR 0041, Punkt 7; ADR 0032, Fortschreibung vom 26.11.2026): der
         * Hypothesensatz, geparst wie eine Zeile der Vorstufe. Beleg ist nie dieser Satz, sondern der
         * Quellstellenbeleg ({@link Derivation#source()}); die Aussage belegt nur den Ist-Zustand des Programms.
         */
        PROGRAMM
    }

    /**
     * Kennzeichen der Vorstufe an einer Aussage aus einem vereinfachten Satz (ADR 0032, FL3). Ein eigenes Feld mit
     * Herkunft „Vorstufe“, nie in den Zeitbezug ({@link Temporality}) übersetzt.
     */
    public enum StageFlag {
        /** Aussage aus dem Original: kein Kennzeichen. */
        NONE,
        /** T: Tatsache. */
        FACT,
        /** B: Bedingung. */
        CONDITION,
        /** P: Planung oder Absicht. */
        PLANNING,
        /** V: verworfen oder früher. */
        DISCARDED
    }

    /**
     * Woher eine Aussage stammt (ADR 0032, FL3 und FL4).
     *
     * @param origin             Original, Vorstufe oder Korrektur des Prüfschritts
     * @param stageFlag          Kennzeichen der Vorstufe; {@link StageFlag#NONE} beim Original und bei einer Korrektur
     * @param simplifiedSentence der vereinfachte Satz (Vorstufe) oder die Korrektur (Modell), aus dem die Aussage
     *                           stammt, damit die Oberfläche ihn neben dem Originalsatz zeigen kann; {@code null} beim
     *                           Original
     * @param corrects           bei einer Korrektur die ID der Aussage, die sie korrigiert; sonst {@code null}
     * @param section            bei einer Aussage der Vorstufe der Abschnitt, den das Modell zu ihr sah (der
     *                           Prüfschritt zeigt ihn als Zusammenhang); {@code null} sonst und bei Analysen vor FL4
     * @param source             bei einer Aussage aus der Programmanalyse ({@link Origin#PROGRAMM}) ihr
     *                           Quellstellenbeleg; sonst {@code null} (Beleg ist dann der Originalsatz)
     */
    public record Derivation(Origin origin, StageFlag stageFlag, String simplifiedSentence, Origin featuresFrom,
                             String corrects, Section section, SourceLocationEvidence source) {

        /** Aus dem Originalsatz (auch alle Analysen vor FL3). */
        public static final Derivation ORIGINAL = new Derivation(Origin.ORIGINAL, StageFlag.NONE, null,
                Origin.ORIGINAL);

        /**
         * @param featuresFrom woher Verneinung, Verbindlichkeit und Zeitbezug stammen: {@link Origin#ORIGINAL}, wenn
         *                     das Original eine Aussage zum selben Verb hat (übernommen), sonst die eigene Herkunft
         */
        public Derivation {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(stageFlag, "stageFlag");
            featuresFrom = featuresFrom == null ? origin : featuresFrom;
            if (origin == Origin.ORIGINAL && (stageFlag != StageFlag.NONE || simplifiedSentence != null
                    || featuresFrom != Origin.ORIGINAL || corrects != null)) {
                throw new IllegalArgumentException("Eine Aussage aus dem Original hat kein Kennzeichen der Vorstufe");
            }
            if (origin == Origin.VORSTUFE && (stageFlag == StageFlag.NONE || simplifiedSentence == null
                    || simplifiedSentence.isBlank() || corrects != null)) {
                throw new IllegalArgumentException("Eine Aussage der Vorstufe braucht Kennzeichen und Satz");
            }
            if (origin == Origin.MODELL && (stageFlag != StageFlag.NONE || simplifiedSentence == null
                    || simplifiedSentence.isBlank() || corrects == null || corrects.isBlank())) {
                throw new IllegalArgumentException("Eine Korrektur braucht ihren Satz und die korrigierte Aussage");
            }
            if (section != null && origin != Origin.VORSTUFE) {
                throw new IllegalArgumentException("Nur eine Aussage der Vorstufe hat einen Abschnitt");
            }
            if (origin == Origin.PROGRAMM && (stageFlag != StageFlag.NONE || simplifiedSentence == null
                    || simplifiedSentence.isBlank() || corrects != null || source == null)) {
                throw new IllegalArgumentException("Eine Aussage der Programmanalyse braucht ihren Satz und ihren "
                        + "Quellstellenbeleg");
            }
            if (source != null && origin != Origin.PROGRAMM) {
                throw new IllegalArgumentException("Nur eine Aussage der Programmanalyse hat einen Quellstellenbeleg");
            }
        }

        /** Ohne Quellstellenbeleg (alle Herkünfte außer {@link Origin#PROGRAMM}). */
        public Derivation(Origin origin, StageFlag stageFlag, String simplifiedSentence, Origin featuresFrom,
                          String corrects, Section section) {
            this(origin, stageFlag, simplifiedSentence, featuresFrom, corrects, section, null);
        }

        /** Ohne Abschnitt. */
        public Derivation(Origin origin, StageFlag stageFlag, String simplifiedSentence, Origin featuresFrom,
                          String corrects) {
            this(origin, stageFlag, simplifiedSentence, featuresFrom, corrects, null);
        }

        /** Ohne Bezug auf eine korrigierte Aussage und ohne Abschnitt (Original und Vorstufe). */
        public Derivation(Origin origin, StageFlag stageFlag, String simplifiedSentence, Origin featuresFrom) {
            this(origin, stageFlag, simplifiedSentence, featuresFrom, null, null);
        }

        /** Aus einem vereinfachten Satz, mit eigenen Merkmalen. */
        public static Derivation vorstufe(StageFlag stageFlag, String simplifiedSentence) {
            return new Derivation(Origin.VORSTUFE, stageFlag, simplifiedSentence, Origin.VORSTUFE);
        }

        /**
         * Aus der Korrektur des Prüfschritts (ADR 0032, FL4).
         *
         * @param correction   der Satz des Modells
         * @param corrects     ID der korrigierten Aussage
         * @param featuresFrom {@link Origin#ORIGINAL}, wenn die Merkmale aus der Original-Aussage desselben Verbs stammen
         */
        public static Derivation modell(String correction, String corrects, Origin featuresFrom) {
            return new Derivation(Origin.MODELL, StageFlag.NONE, correction, featuresFrom, corrects);
        }

        /**
         * Aus einer KI-Hypothese der Programmanalyse (ADR 0041, Punkt 7), mit eigenen Merkmalen.
         *
         * @param hypothesis der Hypothesensatz, wie ihn die Oberfläche zeigt; nie Beleg
         * @param source     der Quellstellenbeleg
         */
        public static Derivation programm(String hypothesis, SourceLocationEvidence source) {
            return new Derivation(Origin.PROGRAMM, StageFlag.NONE, hypothesis, Origin.PROGRAMM, null, null,
                    Objects.requireNonNull(source, "source"));
        }

        /**
         * Ob die Aussage eine eigene Stelle belegt und nicht neben ihrer Original-Aussage steht: Originalsatz oder
         * Programmanalyse. Aussagen der Vorstufe und Korrekturen zählten ihre Stelle sonst doppelt (ADR 0032); eine
         * Programmaussage hat kein Original im Lesetext und zählt als Ist-Zustand (Fortschreibung 26.11.2026).
         */
        public boolean ownSite() {
            return origin == Origin.ORIGINAL || origin == Origin.PROGRAMM;
        }

        /** Dieselbe Herkunft mit Merkmalen aus der Original-Aussage desselben Verbs. */
        public Derivation withFeaturesFromOriginal() {
            return new Derivation(origin, stageFlag, simplifiedSentence, Origin.ORIGINAL, corrects, section, source);
        }

        /** Dieselbe Herkunft mit dem Abschnitt der Vorstufe. */
        public Derivation withSection(Section other) {
            return new Derivation(origin, stageFlag, simplifiedSentence, featuresFrom, corrects, other, source);
        }
    }

    /**
     * Ein Abschnitt der Seite als Satzindizes (ab 0, einschließlich): die Zeilen, die die Vorstufe in einem Aufruf an
     * das Modell gab (ADR 0032, FL3); leere Sätze darin gingen nicht mit.
     */
    public record Section(int first, int last) {

        public Section {
            if (first < 0 || last < first) {
                throw new IllegalArgumentException("Abschnitt " + first + ".." + last + " ungültig");
            }
        }

        /** Ob der Satz im Abschnitt liegt. */
        public boolean contains(int sentence) {
            return sentence >= first && sentence <= last;
        }
    }

    /** Urteil des Prüfschritts über eine Aussage (ADR 0032, FL4). */
    public enum Verdict {
        /** Die Aussage gibt den Satz richtig wieder; hebt nie eine Stufe. */
        STIMMT,
        /** Richtig, aber es fehlt etwas; senkt die Stufe nur mit gültiger Korrektur. */
        UNVOLLSTAENDIG,
        /** Die Aussage gibt den Satz falsch wieder; senkt die wirksame Stufe, löscht nie. */
        FALSCH
    }

    /**
     * Ergebnis des Prüfschritts an einer Aussage (ADR 0032, FL4). Ein eigenes Feld neben der Stufe des Parsers und der
     * fachlichen Prüfung durch Menschen ({@link Review}); überschreibt keins von beiden. Fällt mit dem Entzug der
     * KI-Freigabe und vor jedem neuen Prüflauf weg, dann steht die Aussage genau wie vorher da.
     *
     * @param verdict   Urteil des Modells
     * @param version   Modell und Anweisung ({@code StatementChecks#version()})
     * @param corrected ob eine gültige Korrektur als eigene Aussage ({@link Origin#MODELL}) hinzukam
     */
    public record Check(Verdict verdict, String version, boolean corrected) {

        /** Grund in {@link Statement#effectiveQualityReasons()}: das Modell hält die Aussage für falsch. */
        public static final String MODEL_FALSE = "MODEL_FALSE";
        /** Grund in {@link Statement#effectiveQualityReasons()}: das Modell hat die Aussage vervollständigt. */
        public static final String MODEL_CORRECTED = "MODEL_CORRECTED";

        public Check {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(version, "version");
            if (corrected && verdict != Verdict.UNVOLLSTAENDIG) {
                throw new IllegalArgumentException("Nur eine unvollständige Aussage hat eine Korrektur");
            }
        }

        /** Ob das Urteil die wirksame Stufe senkt: falsch, oder unvollständig mit gültiger Korrektur. */
        public boolean lowers() {
            return verdict == Verdict.FALSCH || corrected;
        }
    }

    public record Statement(String id, int sentence, int start, int end, String predicateText, String predicateLemma,
                            Voice voice, Clause clause, boolean core, List<Argument> arguments, Set<String> findings,
                            int paragraph, Quality quality, List<String> qualityReasons, Review review,
                            Features features, Derivation derivation, Check check) {

        public Statement {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(predicateText, "predicateText");
            Objects.requireNonNull(voice, "voice");
            Objects.requireNonNull(clause, "clause");
            Objects.requireNonNull(quality, "quality");
            Objects.requireNonNull(review, "review");
            Objects.requireNonNull(features, "features");
            derivation = derivation == null ? Derivation.ORIGINAL : derivation;
            arguments = List.copyOf(arguments);
            findings = Set.copyOf(findings);
            qualityReasons = List.copyOf(qualityReasons);
        }

        /** Ohne Urteil des Prüfschritts (ADR 0032, FL4). */
        public Statement(String id, int sentence, int start, int end, String predicateText, String predicateLemma,
                         Voice voice, Clause clause, boolean core, List<Argument> arguments, Set<String> findings,
                         int paragraph, Quality quality, List<String> qualityReasons, Review review,
                         Features features, Derivation derivation) {
            this(id, sentence, start, end, predicateText, predicateLemma, voice, clause, core, arguments, findings,
                    paragraph, quality, qualityReasons, review, features, derivation, null);
        }

        /** Eine Aussage aus dem Original ({@link Derivation#ORIGINAL}), etwa jede Analyse vor ADR 0032, FL3. */
        public Statement(String id, int sentence, int start, int end, String predicateText, String predicateLemma,
                         Voice voice, Clause clause, boolean core, List<Argument> arguments, Set<String> findings,
                         int paragraph, Quality quality, List<String> qualityReasons, Review review,
                         Features features) {
            this(id, sentence, start, end, predicateText, predicateLemma, voice, clause, core, arguments, findings,
                    paragraph, quality, qualityReasons, review, features, Derivation.ORIGINAL);
        }

        /** Eine Aussage ohne Merkmale (Analysen vor ADR 0032, FL2): Merkmale {@link Features#UNKNOWN}. */
        public Statement(String id, int sentence, int start, int end, String predicateText, String predicateLemma,
                         Voice voice, Clause clause, boolean core, List<Argument> arguments, Set<String> findings,
                         int paragraph, Quality quality, List<String> qualityReasons, Review review) {
            this(id, sentence, start, end, predicateText, predicateLemma, voice, clause, core, arguments, findings,
                    paragraph, quality, qualityReasons, review, Features.UNKNOWN);
        }

        /**
         * Eine Aussage ohne Qualitätsbewertung (ältere Analysen, andere Analysatoren): {@link Quality#UNCERTAIN} mit
         * Grund {@value #NOT_ASSESSED}, Absatz unbekannt ({@value #UNKNOWN_PARAGRAPH}), ungeprüft.
         */
        public Statement(String id, int sentence, int start, int end, String predicateText, String predicateLemma,
                         Voice voice, Clause clause, boolean core, List<Argument> arguments, Set<String> findings) {
            this(id, sentence, start, end, predicateText, predicateLemma, voice, clause, core, arguments, findings,
                    UNKNOWN_PARAGRAPH, Quality.UNCERTAIN, List.of(NOT_ASSESSED), Review.UNREVIEWED);
        }

        /** Grund für Aussagen ohne Bewertung. */
        public static final String NOT_ASSESSED = "NOT_ASSESSED";

        /** Absatz für Aussagen ohne Absatzangabe. */
        public static final int UNKNOWN_PARAGRAPH = -1;

        /**
         * Die wirksame Stufe (ADR 0032, FL4): die Stufe des Parsers ({@link #quality()}), außer das Urteil des
         * Prüfschritts senkt sie ({@link Check#lowers()}): dann höchstens {@link Quality#UNCERTAIN}. Ein Urteil hebt nie;
         * die Stufe des Parsers bleibt gespeichert. Graph, Export und Verbzählungen filtern nach dieser Stufe.
         */
        public Quality effectiveQuality() {
            return check != null && check.lowers() && quality == Quality.PLAUSIBLE ? Quality.UNCERTAIN : quality;
        }

        /** Die Gründe zu {@link #effectiveQuality()}: die des Parsers, dazu der Grund des Urteils, wenn es senkt. */
        public List<String> effectiveQualityReasons() {
            if (check == null || !check.lowers()) {
                return qualityReasons;
            }
            List<String> reasons = new java.util.ArrayList<>(qualityReasons);
            reasons.add(check.verdict() == Verdict.FALSCH ? Check.MODEL_FALSE : Check.MODEL_CORRECTED);
            return List.copyOf(reasons);
        }

        /** Dieselbe Aussage mit diesem Urteil oder ohne ({@code null}). */
        public Statement withCheck(Check other) {
            return new Statement(id, sentence, start, end, predicateText, predicateLemma, voice, clause, core,
                    arguments, findings, paragraph, quality, qualityReasons, review, features, derivation, other);
        }

        /** Dieselbe Aussage mit dieser fachlichen Prüfung, etwa der wirksamen aus {@link StatementReviews}. */
        public Statement withReview(Review other) {
            return new Statement(id, sentence, start, end, predicateText, predicateLemma, voice, clause, core,
                    arguments, findings, paragraph, quality, qualityReasons, other, features, derivation, check);
        }

        /** Dieselbe Aussage mit dieser Herkunft. */
        public Statement withDerivation(Derivation other) {
            return new Statement(id, sentence, start, end, predicateText, predicateLemma, voice, clause, core,
                    arguments, findings, paragraph, quality, qualityReasons, review, features, other, check);
        }

        /** Alle Beteiligten mit dieser Rolle. */
        public List<Argument> withRole(Role role) {
            return arguments.stream().filter(argument -> argument.role() == role).toList();
        }

        /** Hinweis einer Zustandsaussage mit Eigenschaft („ist unvollständig“, ADR 0032). */
        public static final String STATE_PROPERTY = "STATE_PROPERTY";
        /** Hinweis einer Zustandsaussage mit Rollennomen („ist Ansprechpartner“, „ist eine Förderung“). */
        public static final String STATE_ROLE_NOUN = "STATE_ROLE_NOUN";

        /**
         * Ob dies eine Zustandsaussage ist (Kopula mit Adjektiv oder Rollennomen, Träger {@link Role#BEARER}). Ihr
         * Prädikat ist kein Verb: Verblisten, Valenz und Verbzählungen lassen sie aus.
         */
        public boolean state() {
            return findings.contains(STATE_PROPERTY) || findings.contains(STATE_ROLE_NOUN);
        }

        /** Rollennomen einer Zustandsaussage ({@code Ansprechpartner}) oder {@code null}. */
        public String roleNoun() {
            return findings.contains(STATE_ROLE_NOUN) ? predicateLemma : null;
        }

        /** Ob alle Beteiligten aus sicheren Parserkanten oder der Wortliste stammen. */
        public boolean certain() {
            return arguments.stream().allMatch(argument -> argument.certain()
                    && (argument.basis() == Basis.PARSER || argument.basis() == Basis.LEXICON));
        }
    }

    /**
     * Extraktionsqualität einer Aussage (Nachtrag Ursachenmatrix zu ADR 0024): beschreibt nur die maschinelle
     * Ableitung, keine fachliche Wahrheit; keine Prozentwerte.
     */
    public enum Quality {
        /** Syntaktisch ausreichend gestützte Relation. */
        PLAUSIBLE,
        /** Verwertbarer Kandidat mit ungeklärter Zuordnung. */
        UNCERTAIN,
        /** Erkannte Fehlkonstruktion; keine fachliche Relation. */
        INVALID
    }

    /**
     * Fachliche Prüfung einer Aussage durch einen Menschen (ADR 0032, Umsetzung W3). Gesetzt und zurückgenommen wird sie
     * je Aussage in der Ablage der Entscheidungen ({@code StandingDecision#review()}, nur für die Ablage des Analysators,
     * in der sie gesetzt wurde), nie in der Analyse selbst. Die Lesewege ({@link StatementReviews}) legen sie über die
     * gespeicherte Aussage: Die Entscheidung gilt, ohne sie bleibt der gespeicherte Wert (in der Regel
     * {@link #UNREVIEWED}).
     */
    public enum Review {
        UNREVIEWED,
        CONFIRMED,
        REJECTED
    }

    public enum Voice {
        ACTIVE,
        PASSIVE
    }

    public enum Clause {
        MAIN,
        COORDINATED,
        SUBORDINATE
    }

    /**
     * Ein Beteiligter einer Aussage.
     *
     * @param role     semantische Rolle
     * @param mention  ID der Erwähnung
     * @param relation UD-Relation ({@code nsubj}, {@code obj}, {@code obl:arg}, {@code nmod}) oder Vorschlag des
     *                 Rückfalls ({@code obj?})
     * @param marker   Präposition im Wortlaut oder {@code null}
     * @param priority Rang
     * @param basis    Herkunft der Zuordnung
     * @param certain  {@code false} bei abgeleiteter, widersprüchlicher oder unbestätigter Zuordnung
     */
    public record Argument(Role role, String mention, String relation, String marker, Priority priority, Basis basis,
                           boolean certain) {

        public Argument {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(mention, "mention");
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(basis, "basis");
        }
    }

    /** Semantische Rolle wie im Aussagenmodell des Enterprise AI Client und in ADR 0005. */
    public enum Role {
        ACTOR,
        AFFECTED_OBJECT,
        RECIPIENT,
        PREPOSITIONAL_OBJECT,
        INSTRUMENT,
        TIME,
        CIRCUMSTANCE,
        /** Träger eines Zustands oder einer Eigenschaft (Subjekt einer Zustandsaussage, ADR 0032); handelt nie. */
        BEARER
    }

    public enum Priority {
        CORE,
        SUPPLEMENTARY,
        CONTEXT
    }

    /** Herkunft einer Zuordnung; alles außer {@link #PARSER} ist eine Ableitung. */
    public enum Basis {
        PARSER,
        COORDINATION,
        CASE_HEURISTIC,
        LEXICON,
        /** Strukturelle Heuristik gegen eine erkennbar fehlerhafte Parserkante; immer unsicher. */
        HEURISTIC,
        /**
         * Vom Sprachmodell des KI-Analysators zugeordnet (Fahrplan Paket A2), der Wortlaut steht wörtlich im Satz; keine
         * Parserkante. Zeigt an jeder Aussage, dass sie vom Modell stammt.
         */
        MODEL
    }

    /**
     * Ein nominaler Bezug ({@code der Beleg des Kunden}): der Text verbindet zwei Gruppen. Kein Besitz, keine Herkunft.
     *
     * @param id        eindeutige ID
     * @param head      Erwähnung, an der die Ergänzung hängt
     * @param dependent Erwähnung der Ergänzung
     * @param relation  UD-Relation ({@code nmod}, {@code nmod:poss})
     * @param marker    Präposition im Wortlaut oder {@code null}
     * @param kind      Art nach Form
     * @param basis     {@link Basis#PARSER} oder {@link Basis#CASE_HEURISTIC}
     */
    public record Attachment(String id, String head, String dependent, String relation, String marker,
                             AttachmentKind kind, Basis basis) {

        public Attachment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(dependent, "dependent");
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(basis, "basis");
        }
    }

    public enum AttachmentKind {
        GENITIVE,
        PREPOSITIONAL,
        OTHER
    }
}
