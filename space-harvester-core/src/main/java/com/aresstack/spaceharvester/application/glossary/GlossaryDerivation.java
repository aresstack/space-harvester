package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.knowledge.CountedStatements;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Begriffe aus den Aussagen ableiten (ADR 0032, FL5a): je Begriff Wichtigkeit, Verhalten, Eigenschaften,
 * Ist-Beziehungen und eine Einordnung nach Regeln. Grundlage für das Fachglossar ({@link Glossary}); die
 * objektorientierte Analyse liest so aus den Nomen die Struktur und aus den Verben das Verhalten.
 *
 * <p>Reine Auswertung wie die Valenz aus dem Bestand: rechnet nur auf Anfrage über die gespeicherten Analysen der
 * Seiten, die der Aufrufer lesen darf ({@link KnowledgeGraph#analysed(AccessContext)}), ohne Modell, ohne Auftrag im
 * Hintergrund, abbrechbar je Seite. Sie schreibt nichts, weder ins Glossar noch in Analysen. Die Teilzahlen gelten
 * darum immer nur für den Aufrufer und werden nicht gespeichert.
 *
 * <p>Was zählt:
 * <ul>
 *   <li>Begriffe sind Erwähnungen der Art {@code NOUN} oder {@code PROPER_NOUN}, zusammengefasst über
 *       {@link #keyOf}; nie Pronomen mit offenem Bezug, nie Teilsätze ({@code CLAUSE});</li>
 *   <li>nur aktuelle Analysen; veraltete werden übersprungen und gezählt;</li>
 *   <li>ob eine Aussage zählt und mit welcher Stufe, sagt allein {@link CountedStatements}; Verhalten, Eigenschaften
 *       und Ist-Beziehungen nur aus Aussagen, die {@code PLAUSIBLE} zählen, und nur über sicher angebundene
 *       Beteiligte (Parserkante oder Wortliste);</li>
 *   <li>Verhalten nur aus Verbaussagen, verneinte getrennt; Zustandsaussagen ({@link ParsedStatements.Statement#state()})
 *       liefern nie Verhalten;</li>
 *   <li>Eigenschaften ({@code STATE_PROPERTY}) und Ist-Beziehungen ({@code STATE_ROLE_NOUN}) nur als Ist-Zustand:
 *       Merkmale bestimmt, nicht verneint, ohne Modalverb, Zeitbezug {@code GILT}, kein Zustandswechsel
 *       ({@code STATE_CHANGE}), Herkunft {@code ORIGINAL}; alle übrigen Zustandsaussagen werden getrennt gezählt;</li>
 *   <li>Aussagen und Erwähnungen der Vorstufe „Sätze vereinfachen“ ({@link ParsedStatements.Origin#VORSTUFE}) zählen
 *       beim Verhalten getrennt (je Eintrag mit Herkunft) und nie für Wichtigkeit, Einordnung, Eigenschaften oder
 *       Ist-Beziehungen: ein vereinfachter Satz ist nie Beleg.</li>
 * </ul>
 *
 * <p>Die Einordnung nach Regeln entscheidet nur, wenn eine Regel greift; alles Übrige bleibt {@link Category#UNKLAR}
 * und geht später an das Chat-Modell (FL5c). Reihenfolge: technisch (Schreibweise, Kopf-Wortliste), Rolle oder Stelle
 * (Kopf-Wortliste). Kurze oder mehrdeutige Köpfe gelten nur als ganzes Wort oder nach Bindestrich oder Leerzeichen,
 * nie als Ende eines zusammengeschriebenen Worts („Import“ endet auf „port“, „Rechtssystem“ auf „system“). Ist-Beziehungen ergeben noch keine Einordnung und keinen Definitionsvorschlag: dazu braucht es den
 * Artikel am Rollennomen („ist eine Förderung“ = Begriffsbestimmung, „ist Ansprechpartner“ = Rolle), den der Parser
 * noch nicht festhält.
 */
public final class GlossaryDerivation {

    /** Belege je Eintrag höchstens. */
    public static final int MAX_EVIDENCE = 3;

    /** Die Wortlisten der Regeln, an einer Stelle. */
    static final String RULES_RESOURCE = "glossar-regeln.tsv";

    /** Kürzester Kopf, der auch als Ende eines zusammengeschriebenen Worts gilt (Auflage des Architekten). */
    static final int MIN_COMPOUND_HEAD = GlossaryRuleSettings.MIN_COMPOUND_HEAD;
    /**
     * Binnenmajuskel vor Kleinbuchstabe („SharePoint“, nicht „GmbH“, „BAföG“) oder Punkt/Unterstrich im Wort
     * („config.yaml“, „ANTRAG_ID“).
     */
    private static final Pattern TECHNICAL_SHAPE =
            Pattern.compile(".*(\\p{Ll}\\p{Lu}\\p{Ll}|[\\p{L}\\p{N}][._][\\p{L}\\p{N}]).*");

    private GlossaryDerivation() {
    }

    /**
     * Die Wortlisten der Einordnung: die feste Liste ({@value #RULES_RESOURCE}) mit den Wörtern der Einstellung
     * {@code space-harvester.glossary.rules.*} je Art dazu und ohne die ausgeschlossenen (ADR 0032, FL5b).
     *
     * @param words je Art die normalisierten Wörter
     */
    public record Rules(Map<GlossaryRuleSettings.Kind, Set<String>> words) {

        /** Die feste Liste ohne Einstellung. */
        public static final Rules DEFAULT = new Rules(load());

        public Rules {
            Map<GlossaryRuleSettings.Kind, Set<String>> copy = new EnumMap<>(GlossaryRuleSettings.Kind.class);
            words.forEach((kind, set) -> copy.put(kind, Set.copyOf(set)));
            words = Map.copyOf(copy);
        }

        /** Die feste Liste mit den Wörtern der Einstellung. */
        public static Rules of(GlossaryRuleSettings settings) {
            Map<GlossaryRuleSettings.Kind, Set<String>> next = new EnumMap<>(GlossaryRuleSettings.Kind.class);
            for (GlossaryRuleSettings.Kind kind : GlossaryRuleSettings.Kind.values()) {
                Set<String> set = new HashSet<>(DEFAULT.words(kind));
                settings.added(kind).stream().map(TermText::normalize).forEach(set::add);
                settings.excluded(kind).stream().map(TermText::normalize).forEach(set::remove);
                next.put(kind, set);
            }
            return new Rules(next);
        }

        /** Die Wörter einer Art. */
        public Set<String> words(GlossaryRuleSettings.Kind kind) {
            return words.getOrDefault(kind, Set.of());
        }
    }

    /** Einordnung eines Begriffs. */
    public enum Category {
        /** Fachlicher Begriff: gehört ins Fachglossar. */
        FACHLICH,
        /** Technischer Begriff: im Glossar nur unter dem Filter „Technik“. */
        TECHNISCH,
        /** Rolle oder Stelle (wer handelt oder zuständig ist). */
        ROLLE,
        /** Keine Regel greift; entscheidet später das Chat-Modell oder der Mensch. */
        UNKLAR
    }

    /** Regel, die eine Einordnung begründet. */
    public enum CategoryRule {
        /** Schreibweise eines Bezeichners: Binnenmajuskel, Punkt oder Unterstrich im Wort. */
        TECHNICAL_SHAPE,
        /** Kopf in der Wortliste technischer Begriffe ({@value #RULES_RESOURCE}, {@code technik}, {@code technik-wort}). */
        TECHNICAL_HEAD,
        /** Kopf in der Wortliste der Rollen und Stellen ({@value #RULES_RESOURCE}, {@code rolle}, {@code rolle-wort}). */
        ROLE_HEAD,
        /** Keine Regel greift. */
        NONE
    }

    /**
     * Ergebnis.
     *
     * @param terms        Begriffe nach Wichtigkeit ({@link Importance#ORDER}), dann Suchwert
     * @param pages        ausgewertete Seiten und Dokumente
     * @param stalePages   übersprungene veraltete Analysen
     * @param statements   ausgewertete Aussagen (zählen {@code PLAUSIBLE})
     * @param notCounted   übersprungene Aussagen: zählen nicht oder unter {@code PLAUSIBLE}
     * @param notPresent   Zustandsaussagen, die keinen Ist-Zustand beschreiben (verneint, Modalverb, früher,
     *                     geplant, verworfen, Merkmale unbestimmt, Zustandswechsel) oder aus der Vorstufe stammen
     */
    public record Result(List<Term> terms, int pages, int stalePages, int statements, int notCounted,
                         int notPresent) {

        public Result {
            terms = List.copyOf(terms);
        }

        /** Der Begriff mit diesem Wortlaut (über den Suchwert) oder {@code null}. */
        public Term term(String text) {
            String normalized = TermText.normalize(text);
            return terms.stream().filter(t -> t.normalized().equals(normalized)).findFirst().orElse(null);
        }
    }

    /**
     * Ein abgeleiteter Begriff.
     *
     * @param term       Wortlaut: die häufigste Schreibweise seiner Erwähnungen (lexikalischer Schlüssel)
     * @param normalized Suchwert wie im Glossar ({@link #keyOf}, {@link GlossaryEntry#normalized()})
     * @param proper     ob die Erwähnungen überwiegend Eigennamen sind
     * @param importance Wichtigkeit als Teilzahlen
     * @param acts       Verben, in denen der Begriff Handelnder ist (Aktiv)
     * @param undergoes  Verben, die ihn betreffen (Betroffenes im Aktiv und Passiv)
     * @param properties Eigenschaften im Ist-Zustand mit ihm als Träger („zuständig“)
     * @param isA        Ist-Beziehungen: Nomen, das er laut Zustandsaussage ist („Förderung“, „Ansprechpartner“)
     * @param instances  Umkehrung von {@code isA}: Begriffe, die laut Zustandsaussage er sind
     * @param category   Einordnung nach Regeln
     * @param rule       Regel der Einordnung, {@link CategoryRule#NONE} bei {@link Category#UNKLAR}
     */
    public record Term(String term, String normalized, boolean proper, Importance importance, List<Use> acts,
                       List<Use> undergoes, List<Use> properties, List<Use> isA, List<Use> instances,
                       Category category, CategoryRule rule) {

        public Term {
            acts = List.copyOf(acts);
            undergoes = List.copyOf(undergoes);
            properties = List.copyOf(properties);
            isA = List.copyOf(isA);
            instances = List.copyOf(instances);
        }
    }

    /**
     * Wichtigkeit als offene Teilzahlen, nur aus dem Original.
     *
     * @param pages      Seiten und Dokumente mit mindestens einer Erwähnung (Seitenstreuung)
     * @param mentions   Erwähnungen
     * @param asActor    Verbaussagen mit ihm als sicherem Handelnden (Aktiv, auch verneint)
     * @param asAffected Verbaussagen mit ihm als sicherem Betroffenen
     * @param asBearer   Zustandsaussagen im Ist-Zustand mit ihm als sicherem Träger
     */
    public record Importance(int pages, int mentions, int asActor, int asAffected, int asBearer) {

        /** Seitenstreuung zuerst, dann Rolle als Handelnder, dann Erwähnungen. */
        public static final Comparator<Importance> ORDER = Comparator.comparingInt(Importance::pages)
                .thenComparingInt(Importance::asActor).thenComparingInt(Importance::mentions);
    }

    /**
     * Ein gezählter Bezug: Verb, Eigenschaft oder Begriff.
     *
     * @param lemma    Grundform (Verben und Adjektive klein, Begriffe in ihrer häufigsten Schreibweise)
     * @param origin   Original oder Vorstufe, getrennt gezählt
     * @param negated  verneinte Aussagen, getrennt gezählt (nur beim Verhalten)
     * @param count    Aussagen
     * @param evidence bis zu {@link #MAX_EVIDENCE} Belege
     */
    public record Use(String lemma, ParsedStatements.Origin origin, boolean negated, int count,
                      List<Evidence> evidence) {

        public Use {
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * Beleg: der Satz einer Aussage als Position, nie als Text. Passt auf {@code RelationEvidence} des Glossars.
     *
     * @param statementId Aussage-ID
     * @param document    Seite oder Dokument
     * @param start       Satzbeginn im Text der Analyse (inklusive)
     * @param end         Satzende (exklusive)
     * @param inputSha256 Eingabe-Hash des Textes, auf den sich die Positionen beziehen
     */
    public record Evidence(String statementId, DocumentKey document, int start, int end, String inputSha256) {
    }

    /**
     * Schlüssel eines Begriffs, die eine Abbildung zwischen Erwähnung und Glossar: Schreibweise der Grundform
     * ({@link #spelling}), normalisiert wie das Glossar ({@link TermText#normalize}). „Anträge“ mit Grundform „Antrag“
     * trifft so den Glossareintrag „Antrag“.
     */
    public static String keyOf(ParsedStatements.Mention mention) {
        return TermText.normalize(spelling(mention));
    }

    /**
     * Schreibweise der Grundform einer Erwähnung ({@link ParsedStatements.Mention#key()}) in der Schreibung des Textes:
     * Das Sprachmodell liefert Grundformen klein und teils in alter Rechtschreibung („zuschuß“ zu „Zuschuss“). Steht im
     * Text „ss“, wo die Grundform „ß“ hat, gilt die Schreibung des Textes; gleicht die Grundform dann der Wortform bis
     * auf Groß- und Kleinschreibung, gilt die Wortform („batch-job“ zu „Batch-Job“); sonst wird sie großgeschrieben, wenn
     * der Kopf im Text großgeschrieben ist. Die Grundform bleibt sonst, wie sie ist (kein ß zu ss).
     */
    public static String spelling(ParsedStatements.Mention mention) {
        return spelling(mention.key(), mention.headForm());
    }

    /** Wie {@link #spelling(ParsedStatements.Mention)} für eine Grundform und ihre Wortform im Text. */
    static String spelling(String lemma, String form) {
        if (lemma == null || lemma.isEmpty() || form == null || form.isEmpty() || lemma.equals(form)) {
            return lemma;
        }
        String spelled = lemma;
        if (lemma.indexOf('ß') >= 0 && form.indexOf('ß') < 0) {
            String modern = lemma.replace("ß", "ss");
            if (form.toLowerCase(Locale.ROOT).startsWith(modern.toLowerCase(Locale.ROOT))) {
                spelled = modern;
            }
        }
        if (spelled.equalsIgnoreCase(form)) {
            return form;
        }
        if (Character.isUpperCase(form.codePointAt(0)) && Character.isLowerCase(spelled.codePointAt(0))) {
            spelled = spelled.substring(0, Character.charCount(spelled.codePointAt(0))).toUpperCase(Locale.ROOT)
                    + spelled.substring(Character.charCount(spelled.codePointAt(0)));
        }
        return spelled;
    }

    /** Über alle Seiten, die {@code caller} lesen darf. */
    public static Result of(KnowledgeGraph graph, AccessContext caller, BooleanSupplier cancelled) {
        return of(graph.analysed(caller), cancelled);
    }

    /**
     * Über die gegebenen sichtbaren Seiten.
     *
     * @throws CancellationException sobald {@code cancelled} zutrifft (je Seite geprüft)
     */
    public static Result of(Collection<KnowledgeGraph.AnalysedPage> pages, BooleanSupplier cancelled) {
        return of(pages, cancelled, Rules.DEFAULT);
    }

    /**
     * Über die gegebenen sichtbaren Seiten mit diesen Wortlisten.
     *
     * @throws CancellationException sobald {@code cancelled} zutrifft (je Seite geprüft)
     */
    public static Result of(Collection<KnowledgeGraph.AnalysedPage> pages, BooleanSupplier cancelled, Rules rules) {
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(rules, "rules");
        Counting counting = new Counting();
        for (KnowledgeGraph.AnalysedPage page : pages) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            if (page.page().state() != PageStatements.State.CURRENT) {
                counting.stalePages++;
                continue;
            }
            counting.page(page);
        }
        List<Term> list = new ArrayList<>();
        counting.terms.values().forEach(builder -> list.add(builder.build(counting.terms, rules)));
        list.sort(Comparator.comparing(Term::importance, Importance.ORDER.reversed())
                .thenComparing(Term::normalized));
        return new Result(list, counting.pages, counting.stalePages, counting.statements, counting.notCounted,
                counting.notPresent);
    }

    /** Einordnung nach Regeln für einen Wortlaut. Offen für Prüfstand und Tests. */
    public static CategoryRule ruleFor(String term) {
        return ruleFor(term, Rules.DEFAULT);
    }

    /** Einordnung nach Regeln für einen Wortlaut mit diesen Wortlisten. */
    public static CategoryRule ruleFor(String term, Rules rules) {
        String normalized = TermText.normalize(term);
        if (TECHNICAL_SHAPE.matcher(term).matches()) {
            return CategoryRule.TECHNICAL_SHAPE;
        }
        if (endsWithHead(normalized, rules.words(GlossaryRuleSettings.Kind.TECHNICAL_HEADS), true)
                || endsWithHead(normalized, rules.words(GlossaryRuleSettings.Kind.TECHNICAL_WORDS), false)) {
            return CategoryRule.TECHNICAL_HEAD;
        }
        if (endsWithHead(normalized, rules.words(GlossaryRuleSettings.Kind.ROLE_HEADS), true)
                || endsWithHead(normalized, rules.words(GlossaryRuleSettings.Kind.ROLE_WORDS), false)) {
            return CategoryRule.ROLE_HEAD;
        }
        return CategoryRule.NONE;
    }

    /** Einordnung zu einer Regel. */
    public static Category categoryOf(CategoryRule rule) {
        return switch (rule) {
            case TECHNICAL_HEAD, TECHNICAL_SHAPE -> Category.TECHNISCH;
            case ROLE_HEAD -> Category.ROLLE;
            case NONE -> Category.UNKLAR;
        };
    }

    /** Ob eine Zustandsaussage einen Ist-Zustand beschreibt (ADR 0032, Auflage des Architekten). */
    static boolean present(ParsedStatements.Statement statement) {
        ParsedStatements.Features features = statement.features();
        return statement.derivation().origin() == ParsedStatements.Origin.ORIGINAL
                && features.negation() == ParsedStatements.Negation.AFFIRMED
                && features.modality() == ParsedStatements.Modality.NONE
                && features.temporality() == ParsedStatements.Temporality.GILT
                && !statement.findings().contains("STATE_CHANGE");
    }

    private static boolean sure(ParsedStatements.Argument argument) {
        return argument.certain() && (argument.basis() == ParsedStatements.Basis.PARSER
                || argument.basis() == ParsedStatements.Basis.LEXICON);
    }

    private static boolean term(ParsedStatements.Mention mention) {
        return (mention.kind() == ParsedStatements.MentionKind.NOUN
                || mention.kind() == ParsedStatements.MentionKind.PROPER_NOUN)
                && !mention.unresolved() && mention.key() != null && TermText.usable(mention.key());
    }

    /**
     * Ob der Suchwert so heißt oder nach Bindestrich oder Leerzeichen so endet; mit {@code compound} auch als Ende eines
     * zusammengeschriebenen Worts mit mindestens drei Buchstaben davor („Antragsdatenbank“).
     */
    private static boolean endsWithHead(String normalized, Set<String> heads, boolean compound) {
        if (heads.contains(normalized)) {
            return true;
        }
        for (String head : heads) {
            if (normalized.endsWith(" " + head) || normalized.endsWith("-" + head)
                    || compound && normalized.length() >= head.length() + 3 && normalized.endsWith(head)) {
                return true;
            }
        }
        return false;
    }

    private static Map<GlossaryRuleSettings.Kind, Set<String>> load() {
        Map<GlossaryRuleSettings.Kind, Set<String>> words = new EnumMap<>(GlossaryRuleSettings.Kind.class);
        try (InputStream input = GlossaryDerivation.class.getResourceAsStream(RULES_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Wortliste fehlt: " + RULES_RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t");
                if (columns.length != 2) {
                    throw new IllegalStateException("Ungültige Zeile in " + RULES_RESOURCE + ": " + line);
                }
                GlossaryRuleSettings.Kind kind = GlossaryRuleSettings.Kind.ofResource(columns[0].strip());
                if (kind == null) {
                    throw new IllegalStateException("Unbekannte Art in " + RULES_RESOURCE + ": " + line);
                }
                String word = TermText.normalize(columns[1]);
                if (kind.compound() && word.length() < MIN_COMPOUND_HEAD) {
                    throw new IllegalStateException("Kopf unter " + MIN_COMPOUND_HEAD + " Buchstaben gilt nur als "
                            + "ganzes Wort (" + kind.resourceKind() + "-wort): " + word);
                }
                words.computeIfAbsent(kind, k -> new HashSet<>()).add(word);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return words;
    }

    /** Zählstand über alle Seiten. */
    private static final class Counting {
        final Map<String, Builder> terms = new TreeMap<>();
        int pages;
        int stalePages;
        int statements;
        int notCounted;
        int notPresent;

        void page(KnowledgeGraph.AnalysedPage page) {
            pages++;
            ParsedStatements result = page.analysis().result();
            Map<String, ParsedStatements.Mention> mentions = new HashMap<>();
            result.mentions().forEach(mention -> mentions.put(mention.id(), mention));
            Set<String> stageOnly = stageMentions(result);
            Set<String> onPage = new HashSet<>();
            for (ParsedStatements.Mention mention : result.mentions()) {
                if (stageOnly.contains(mention.id()) || !term(mention)) {
                    continue;
                }
                Builder builder = builder(keyOf(mention));
                builder.mention(mention);
                if (onPage.add(builder.normalized)) {
                    builder.pages++;
                }
            }
            for (ParsedStatements.Statement statement : result.statements()) {
                if (!CountedStatements.plausible(statement)) {
                    notCounted++;
                    continue;
                }
                statements++;
                Evidence evidence = evidence(statement, result.sentences(), page);
                if (statement.state()) {
                    state(statement, mentions, evidence);
                } else {
                    verb(statement, mentions, evidence);
                }
            }
        }

        private void state(ParsedStatements.Statement statement, Map<String, ParsedStatements.Mention> mentions,
                           Evidence evidence) {
            if (!present(statement)) {
                notPresent++;
                return;
            }
            String lemma = statement.predicateLemma() == null ? statement.predicateText() : statement.predicateLemma();
            for (ParsedStatements.Argument argument : statement.withRole(ParsedStatements.Role.BEARER)) {
                ParsedStatements.Mention mention = mentions.get(argument.mention());
                if (mention == null || !term(mention) || !sure(argument)) {
                    continue;
                }
                Builder bearer = builder(keyOf(mention));
                bearer.asBearer++;
                if (statement.roleNoun() == null) {
                    bearer.properties.add(lemma.toLowerCase(Locale.ROOT), ParsedStatements.Origin.ORIGINAL, false,
                            evidence);
                    continue;
                }
                String[] words = statement.predicateText().split(" ");
                String roleNoun = spelling(statement.roleNoun(), words[words.length - 1]);
                Builder noun = builder(TermText.normalize(roleNoun));
                noun.forms.putIfAbsent(roleNoun, 0);
                bearer.isA.add(noun.normalized, ParsedStatements.Origin.ORIGINAL, false, evidence);
                noun.instances.add(bearer.normalized, ParsedStatements.Origin.ORIGINAL, false, evidence);
            }
        }

        private void verb(ParsedStatements.Statement statement, Map<String, ParsedStatements.Mention> mentions,
                          Evidence evidence) {
            ParsedStatements.Origin origin = statement.derivation().origin();
            boolean negated = statement.features().negation() == ParsedStatements.Negation.NEGATED;
            String lemma = statement.predicateLemma() == null ? statement.predicateText() : statement.predicateLemma();
            String verb = lemma.toLowerCase(Locale.ROOT);
            for (ParsedStatements.Argument argument : statement.arguments()) {
                boolean actor = argument.role() == ParsedStatements.Role.ACTOR
                        && statement.voice() == ParsedStatements.Voice.ACTIVE;
                boolean affected = argument.role() == ParsedStatements.Role.AFFECTED_OBJECT;
                if (!actor && !affected || !sure(argument)) {
                    continue;
                }
                ParsedStatements.Mention mention = mentions.get(argument.mention());
                if (mention == null || !term(mention)) {
                    continue;
                }
                Builder builder = builder(keyOf(mention));
                (actor ? builder.acts : builder.undergoes).add(verb, origin, negated, evidence);
                if (origin == ParsedStatements.Origin.ORIGINAL) {
                    if (actor) {
                        builder.asActor++;
                    } else {
                        builder.asAffected++;
                    }
                }
            }
        }

        private Builder builder(String normalized) {
            return terms.computeIfAbsent(normalized, Builder::new);
        }

        private static Evidence evidence(ParsedStatements.Statement statement,
                                         List<ParsedStatements.Sentence> sentences, KnowledgeGraph.AnalysedPage page) {
            int index = statement.sentence();
            boolean known = index >= 0 && index < sentences.size();
            int start = known ? sentences.get(index).start() : statement.start();
            int end = known ? sentences.get(index).end() : Math.max(statement.end(), statement.start() + 1);
            return new Evidence(statement.id(), page.page().document(), start, end,
                    page.analysis().inputSha256());
        }

        /** Erwähnungen, auf die nur Aussagen anderer Herkunft als das Original zeigen (Vorstufe, später Modell). */
        private static Set<String> stageMentions(ParsedStatements result) {
            Set<String> stage = new HashSet<>();
            Set<String> original = new HashSet<>();
            for (ParsedStatements.Statement statement : result.statements()) {
                Set<String> target = statement.derivation().origin() != ParsedStatements.Origin.ORIGINAL ? stage
                        : original;
                statement.arguments().forEach(argument -> target.add(argument.mention()));
            }
            stage.removeAll(original);
            return stage;
        }
    }

    /** Zählt Bezüge je Grundform, Herkunft und Verneinung. */
    private static final class Uses {
        private final Map<UseKey, Counter> counters = new TreeMap<>(Comparator.comparing(UseKey::lemma)
                .thenComparing(UseKey::origin).thenComparing(UseKey::negated));

        void add(String lemma, ParsedStatements.Origin origin, boolean negated, Evidence evidence) {
            Counter counter = counters.computeIfAbsent(new UseKey(lemma, origin, negated), k -> new Counter());
            counter.count++;
            if (counter.evidence.size() < MAX_EVIDENCE && counter.evidence.stream()
                    .noneMatch(e -> e.statementId().equals(evidence.statementId()))) {
                counter.evidence.add(evidence);
            }
        }

        /** Original vor Vorstufe, bejaht vor verneint, dann nach Anzahl. */
        List<Use> list(UnaryOperator<String> display) {
            List<Use> list = new ArrayList<>();
            counters.forEach((key, counter) -> list.add(new Use(display.apply(key.lemma()), key.origin(),
                    key.negated(), counter.count, counter.evidence)));
            list.sort(Comparator.comparing(Use::origin).thenComparing(Use::negated)
                    .thenComparing(Comparator.comparingInt(Use::count).reversed()));
            return list;
        }
    }

    private record UseKey(String lemma, ParsedStatements.Origin origin, boolean negated) {
    }

    private static final class Counter {
        int count;
        final List<Evidence> evidence = new ArrayList<>();
    }

    private static final class Builder {
        final String normalized;
        final Map<String, Integer> forms = new HashMap<>();
        int proper;
        int pages;
        int mentions;
        int asActor;
        int asAffected;
        int asBearer;
        final Uses acts = new Uses();
        final Uses undergoes = new Uses();
        final Uses properties = new Uses();
        final Uses isA = new Uses();
        final Uses instances = new Uses();

        Builder(String normalized) {
            this.normalized = normalized;
        }

        void mention(ParsedStatements.Mention mention) {
            mentions++;
            forms.merge(spelling(mention), 1, Integer::sum);
            if (mention.kind() == ParsedStatements.MentionKind.PROPER_NOUN) {
                proper++;
            }
        }

        /** Häufigste Schreibweise, bei Gleichstand die alphabetisch erste; ohne Erwähnung der Suchwert. */
        String display() {
            return forms.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey).orElse(normalized);
        }

        Term build(Map<String, Builder> all, Rules rules) {
            String term = display();
            CategoryRule rule = ruleFor(term, rules);
            UnaryOperator<String> same = lemma -> lemma;
            UnaryOperator<String> shown = key -> all.get(key).display();
            return new Term(term, normalized, proper * 2 > mentions, new Importance(pages, mentions, asActor,
                    asAffected, asBearer), acts.list(same), undergoes.list(same), properties.list(same),
                    isA.list(shown), instances.list(shown), categoryOf(rule), rule);
        }
    }
}
