package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.Category;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.CategoryRule;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Argument;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Basis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.MentionKind;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Priority;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Quality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Role;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Voice;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Begriffe aus den Aussagen ableiten (ADR 0032, FL5a) an erfundenen, von Hand gebauten Analysen. */
class GlossaryDerivationTest {

    /** Baut die Analyse einer erfundenen Seite Satz für Satz. */
    private static final class PageBuilder {
        final String id;
        final List<ParsedStatements.Sentence> sentences = new ArrayList<>();
        final List<ParsedStatements.Mention> mentions = new ArrayList<>();
        final List<ParsedStatements.Statement> statements = new ArrayList<>();
        int offset;
        int counter;

        PageBuilder(String id) {
            this.id = id;
        }

        int sentence(int length) {
            sentences.add(new ParsedStatements.Sentence(sentences.size(), offset, offset + length));
            offset += length + 1;
            return sentences.size() - 1;
        }

        String mention(int sentence, String key, MentionKind kind) {
            String mentionId = "wiki/" + id + "@1#s" + sentence + ".t" + (counter++);
            ParsedStatements.Sentence s = sentences.get(sentence);
            mentions.add(new ParsedStatements.Mention(mentionId, sentence, s.start(), s.start() + key.length(), key,
                    key, key, kind, false, Set.of()));
            return mentionId;
        }

        String noun(int sentence, String key) {
            return mention(sentence, key, MentionKind.NOUN);
        }

        void statement(int sentence, String lemma, Voice voice, Quality quality, ParsedStatements.Derivation derivation,
                       Set<String> findings, Argument... arguments) {
            statement(sentence, lemma, voice, quality, derivation, FACT, findings, arguments);
        }

        void statement(int sentence, String lemma, Voice voice, Quality quality, ParsedStatements.Derivation derivation,
                       ParsedStatements.Features features, Set<String> findings, Argument... arguments) {
            ParsedStatements.Sentence s = sentences.get(sentence);
            statements.add(new ParsedStatements.Statement("wiki/" + id + "@1#s" + sentence + ".v" + (counter++),
                    sentence, s.start() + 4, s.start() + 8, lemma, lemma, voice, ParsedStatements.Clause.MAIN, true,
                    List.of(arguments), findings, 0, quality, List.of(), ParsedStatements.Review.UNREVIEWED,
                    features, derivation));
        }

        void verb(int sentence, String lemma, Argument... arguments) {
            statement(sentence, lemma, Voice.ACTIVE, Quality.PLAUSIBLE, null, Set.of(), arguments);
        }

        void state(int sentence, String predicate, String finding, Argument bearer) {
            statement(sentence, predicate, Voice.ACTIVE, Quality.PLAUSIBLE, null, Set.of(finding), bearer);
        }

        KnowledgeGraph.AnalysedPage build(PageStatements.State state) {
            PageKey key = new PageKey("wiki", id);
            ParsedStatements result = new ParsedStatements(sentences, mentions, statements, List.of(), List.of(),
                    null);
            PageStatementAnalysis analysis = new PageStatementAnalysis(key, "FOE", "Seite " + id, "1",
                    "sha256:" + "a".repeat(64), "fake", "1", Instant.EPOCH, result);
            return new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, "FOE", "Seite " + id, state,
                    state == PageStatements.State.CURRENT ? null : "text-changed"), analysis);
        }
    }

    private static final ParsedStatements.Features FACT = new ParsedStatements.Features(
            ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT);
    private static final ParsedStatements.Features NEGATED = new ParsedStatements.Features(
            ParsedStatements.Negation.NEGATED, ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT);
    private static final ParsedStatements.Features PLANNED = new ParsedStatements.Features(
            ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GEPLANT);
    private static final ParsedStatements.Features MUST = new ParsedStatements.Features(
            ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.MUSS, ParsedStatements.Temporality.GILT);

    private static ParsedStatements.Statement rejected(PageBuilder page, int sentence, String lemma,
                                                       Argument... arguments) {
        ParsedStatements.Sentence s = page.sentences.get(sentence);
        return new ParsedStatements.Statement("wiki/" + page.id + "@1#s" + sentence + ".v" + (page.counter++),
                sentence, s.start(), s.start() + 4, lemma, lemma, Voice.ACTIVE, ParsedStatements.Clause.MAIN, true,
                List.of(arguments), Set.of(), 0, Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.REJECTED,
                FACT, null);
    }

    private static Argument sure(Role role, String mention) {
        return new Argument(role, mention, "rel", null, Priority.CORE, Basis.PARSER, true);
    }

    private static Argument unsure(Role role, String mention) {
        return new Argument(role, mention, "rel", null, Priority.CORE, Basis.HEURISTIC, false);
    }

    /**
     * Erfundene Seiten: „Der Zuschuss ist eine Förderung. Die Bewilligungsstelle prüft den Antrag. Der Antrag ist
     * vollständig. Das Antragssystem speichert den Antrag.“ und „Die Bewilligungsstelle genehmigt den Zuschuss. Der
     * Antrag wird geprüft.“
     */
    private static List<KnowledgeGraph.AnalysedPage> pages() {
        PageBuilder one = new PageBuilder("1");
        int s0 = one.sentence(33);
        one.state(s0, "Förderung", ParsedStatements.Statement.STATE_ROLE_NOUN,
                sure(Role.BEARER, one.noun(s0, "Zuschuss")));
        int s1 = one.sentence(44);
        one.verb(s1, "prüfen", sure(Role.ACTOR, one.noun(s1, "Bewilligungsstelle")),
                sure(Role.AFFECTED_OBJECT, one.noun(s1, "Antrag")));
        int s2 = one.sentence(30);
        one.state(s2, "vollständig", ParsedStatements.Statement.STATE_PROPERTY,
                sure(Role.BEARER, one.noun(s2, "Antrag")));
        int s3 = one.sentence(40);
        one.verb(s3, "speichern", sure(Role.ACTOR, one.noun(s3, "Antragssystem")),
                sure(Role.AFFECTED_OBJECT, one.noun(s3, "Antrag")));
        PageBuilder two = new PageBuilder("2");
        int t0 = two.sentence(45);
        two.verb(t0, "genehmigen", sure(Role.ACTOR, two.noun(t0, "Bewilligungsstelle")),
                sure(Role.AFFECTED_OBJECT, two.noun(t0, "Zuschuss")));
        int t1 = two.sentence(25);
        two.statement(t1, "prüfen", Voice.PASSIVE, Quality.PLAUSIBLE, null, Set.of(),
                sure(Role.AFFECTED_OBJECT, two.noun(t1, "Antrag")));
        // Vorstufe: eigene Erwähnung, getrennt gezählt
        two.statement(t1, "prüfen", Voice.ACTIVE, Quality.PLAUSIBLE, ParsedStatements.Derivation.vorstufe(
                        ParsedStatements.StageFlag.FACT, "Die Bewilligungsstelle prüft den Antrag."), Set.of(),
                sure(Role.ACTOR, two.noun(t1, "Bewilligungsstelle")), sure(Role.AFFECTED_OBJECT,
                        two.noun(t1, "Antrag")));
        // zu schwach und unsicher: zählen nicht für Verhalten
        two.statement(t1, "löschen", Voice.ACTIVE, Quality.UNCERTAIN, null, Set.of(),
                sure(Role.ACTOR, two.noun(t1, "Kunde")));
        two.verb(t1, "ablehnen", unsure(Role.ACTOR, two.noun(t1, "Prüfer")));
        PageBuilder three = new PageBuilder("3");
        int u0 = three.sentence(20);
        three.verb(u0, "senden", sure(Role.ACTOR, three.noun(u0, "Antragsteller")));
        return List.of(one.build(PageStatements.State.CURRENT), two.build(PageStatements.State.CURRENT),
                three.build(PageStatements.State.STALE));
    }

    @Test
    void derivesImportanceBehaviourPropertiesAndIsRelations() {
        GlossaryDerivation.Result result = GlossaryDerivation.of(pages(), () -> false);

        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.stalePages()).isEqualTo(1);
        assertThat(result.notCounted()).isEqualTo(1);
        assertThat(result.term("Antragsteller")).as("veraltete Analyse").isNull();

        GlossaryDerivation.Term antrag = result.term("antrag");
        assertThat(antrag.term()).isEqualTo("Antrag");
        assertThat(antrag.importance()).isEqualTo(new GlossaryDerivation.Importance(2, 4, 0, 3, 1));
        assertThat(antrag.undergoes()).extracting(GlossaryDerivation.Use::lemma, GlossaryDerivation.Use::origin,
                        GlossaryDerivation.Use::count)
                .containsExactly(tuple("prüfen", Origin.ORIGINAL, 2), tuple("speichern", Origin.ORIGINAL, 1),
                        tuple("prüfen", Origin.VORSTUFE, 1));
        assertThat(antrag.properties()).extracting(GlossaryDerivation.Use::lemma).containsExactly("vollständig");
        assertThat(antrag.acts()).isEmpty();
        GlossaryDerivation.Evidence evidence = antrag.properties().get(0).evidence().get(0);
        assertThat(evidence.document().page()).isEqualTo(new PageKey("wiki", "1"));
        assertThat(evidence.start()).as("Satzbeginn").isEqualTo(79);
        assertThat(evidence.end()).isEqualTo(109);
        assertThat(evidence.inputSha256()).isEqualTo("sha256:" + "a".repeat(64));

        GlossaryDerivation.Term stelle = result.term("Bewilligungsstelle");
        assertThat(stelle.importance().asActor()).as("Vorstufe zählt nicht").isEqualTo(2);
        assertThat(stelle.importance().mentions()).as("Erwähnung nur der Vorstufe zählt nicht").isEqualTo(2);
        assertThat(stelle.acts()).extracting(GlossaryDerivation.Use::lemma, GlossaryDerivation.Use::origin)
                .containsExactly(tuple("genehmigen", Origin.ORIGINAL), tuple("prüfen", Origin.ORIGINAL),
                        tuple("prüfen", Origin.VORSTUFE));
        assertThat(stelle.category()).as("Stelle nur als ganzes Wort").isEqualTo(Category.UNKLAR);
        assertThat(result.term("Prüfer").category()).isEqualTo(Category.ROLLE);

        GlossaryDerivation.Term zuschuss = result.term("Zuschuss");
        assertThat(zuschuss.isA()).extracting(GlossaryDerivation.Use::lemma).containsExactly("Förderung");
        assertThat(zuschuss.category()).as("Ist-Beziehung ordnet noch nicht ein").isEqualTo(Category.UNKLAR);
        GlossaryDerivation.Term foerderung = result.term("Förderung");
        assertThat(foerderung.instances()).extracting(GlossaryDerivation.Use::lemma).containsExactly("Zuschuss");
        assertThat(foerderung.importance().pages()).as("nur als Prädikatsnomen genannt").isZero();

        assertThat(result.term("Antragssystem").category()).as("system nur als ganzes Wort").isEqualTo(Category.UNKLAR);
        assertThat(result.term("Kunde").acts()).as("UNCERTAIN zählt nicht").isEmpty();
        assertThat(result.term("Prüfer").acts()).as("unsicherer Handelnder zählt nicht").isEmpty();

        assertThat(result.terms()).extracting(GlossaryDerivation.Term::term).startsWith("Bewilligungsstelle",
                "Antrag", "Zuschuss");
    }

    @Test
    void onlyPresentStatesGiveIsRelationsAndProperties() {
        PageBuilder page = new PageBuilder("1");
        int s0 = page.sentence(40);
        page.statement(s0, "Förderung", Voice.ACTIVE, Quality.PLAUSIBLE, null, NEGATED,
                Set.of(ParsedStatements.Statement.STATE_ROLE_NOUN), sure(Role.BEARER, page.noun(s0, "Darlehen")));
        int s1 = page.sentence(40);
        page.statement(s1, "zuständig", Voice.ACTIVE, Quality.PLAUSIBLE, null, PLANNED,
                Set.of(ParsedStatements.Statement.STATE_PROPERTY), sure(Role.BEARER, page.noun(s1, "Referat")));
        int s2 = page.sentence(40);
        page.statement(s2, "vollständig", Voice.ACTIVE, Quality.PLAUSIBLE, null, MUST,
                Set.of(ParsedStatements.Statement.STATE_PROPERTY), sure(Role.BEARER, page.noun(s2, "Antrag")));
        int s3 = page.sentence(40);
        page.statement(s3, "bestandskräftig", Voice.ACTIVE, Quality.PLAUSIBLE, null, FACT,
                Set.of(ParsedStatements.Statement.STATE_PROPERTY, "STATE_CHANGE"),
                sure(Role.BEARER, page.noun(s3, "Bescheid")));
        int s4 = page.sentence(40);
        page.statement(s4, "Förderung", Voice.ACTIVE, Quality.PLAUSIBLE, ParsedStatements.Derivation.vorstufe(
                        ParsedStatements.StageFlag.FACT, "Der Zuschuss ist eine Förderung."), FACT,
                Set.of(ParsedStatements.Statement.STATE_ROLE_NOUN), sure(Role.BEARER, page.noun(s4, "Zuschuss")));
        int s5 = page.sentence(40);
        page.statement(s5, "zuständig", Voice.ACTIVE, Quality.PLAUSIBLE, null, ParsedStatements.Features.UNKNOWN,
                Set.of(ParsedStatements.Statement.STATE_PROPERTY), sure(Role.BEARER, page.noun(s5, "Amt")));

        GlossaryDerivation.Result result = GlossaryDerivation.of(List.of(page.build(PageStatements.State.CURRENT)),
                () -> false);

        assertThat(result.notPresent()).isEqualTo(6);
        assertThat(result.term("Darlehen").isA()).as("verneint").isEmpty();
        assertThat(result.term("Förderung")).as("verneint und Vorstufe ergeben keinen Begriff").isNull();
        assertThat(result.term("Referat").properties()).as("geplant").isEmpty();
        assertThat(result.term("Antrag").properties()).as("Modalverb").isEmpty();
        assertThat(result.term("Bescheid").properties()).as("Zustandswechsel").isEmpty();
        assertThat(result.term("Amt").properties()).as("Merkmale unbestimmt").isEmpty();
        assertThat(result.terms()).allSatisfy(term -> assertThat(term.importance().asBearer()).isZero());
    }

    @Test
    void negatedVerbsAreKeptApartAndRejectedStatementsDoNotCount() {
        PageBuilder page = new PageBuilder("1");
        int s0 = page.sentence(40);
        page.verb(s0, "prüfen", sure(Role.ACTOR, page.noun(s0, "Referat")),
                sure(Role.AFFECTED_OBJECT, page.noun(s0, "Antrag")));
        int s1 = page.sentence(40);
        page.statement(s1, "prüfen", Voice.ACTIVE, Quality.PLAUSIBLE, null, NEGATED, Set.of(),
                sure(Role.ACTOR, page.noun(s1, "Referat")), sure(Role.AFFECTED_OBJECT, page.noun(s1, "Antrag")));
        int s2 = page.sentence(40);
        page.statements.add(rejected(page, s2, "genehmigen", sure(Role.ACTOR, page.noun(s2, "Referat"))));

        GlossaryDerivation.Result result = GlossaryDerivation.of(List.of(page.build(PageStatements.State.CURRENT)),
                () -> false);

        assertThat(result.notCounted()).as("vom Menschen abgelehnt").isEqualTo(1);
        assertThat(result.term("Referat").acts()).extracting(GlossaryDerivation.Use::lemma,
                        GlossaryDerivation.Use::negated, GlossaryDerivation.Use::count)
                .containsExactly(tuple("prüfen", false, 1), tuple("prüfen", true, 1));
        assertThat(result.term("Referat").importance().asActor()).isEqualTo(2);
    }

    @Test
    void keyMapsInflectedMentionsOntoTheGlossaryEntry() {
        PageBuilder page = new PageBuilder("1");
        int s = page.sentence(60);
        String[][] mentions = {{"Anträge", "Antrag"}, {"Antrag", "Antrag"}, {"Antrags", "Antrag"},
                {"E-Mails", "E-Mail"}, {"E-Mail", "E-Mail"}, {"e-Mail", "e-Mail"}};
        for (String[] mention : mentions) {
            page.mentions.add(new ParsedStatements.Mention("m" + page.counter++, s, 0, 5, mention[0], mention[0],
                    mention[1], MentionKind.NOUN, false, Set.of()));
        }

        GlossaryDerivation.Result result = GlossaryDerivation.of(List.of(page.build(PageStatements.State.CURRENT)),
                () -> false);

        GlossaryDerivation.Term antrag = result.term("Antrag");
        assertThat(antrag.importance().mentions()).isEqualTo(3);
        assertThat(antrag.normalized()).isEqualTo(GlossaryDerivation.keyOf(page.mentions.get(0)));
        com.aresstack.spaceharvester.application.terms.RelationScope scope =
                com.aresstack.spaceharvester.application.terms.RelationScope.GLOBAL;
        assertThat(GlossaryEntry.idOf(antrag.term(), scope)).isEqualTo(GlossaryEntry.idOf("Antrag", scope));
        GlossaryDerivation.Term mail = result.term("e-mail");
        assertThat(mail.term()).as("häufigste Schreibweise").isEqualTo("E-Mail");
        assertThat(mail.importance().mentions()).isEqualTo(3);
        assertThat(result.term("Anträge")).as("Wortform ist kein eigener Begriff").isNull();
    }

    @Test
    void spellingFollowsTheTextWhereTheModelLemmaIsLowerCaseOrOldSpelling() {
        assertThat(GlossaryDerivation.spelling("zuschuß", "Zuschuss")).isEqualTo("Zuschuss");
        assertThat(GlossaryDerivation.spelling("antrag", "Anträge")).isEqualTo("Antrag");
        assertThat(GlossaryDerivation.spelling("Straße", "Straße")).isEqualTo("Straße");
        assertThat(GlossaryDerivation.spelling("maß", "Maßnahmen")).as("kein ß zu ss").isEqualTo("Maß");
        assertThat(GlossaryDerivation.spelling("prüfen", "prüft")).isEqualTo("prüfen");
        assertThat(GlossaryDerivation.spelling("batch-job", "Batch-Job")).isEqualTo("Batch-Job");
        assertThat(GlossaryDerivation.spelling("https", "HTTPS")).isEqualTo("HTTPS");
        ParsedStatements.Mention mention = new ParsedStatements.Mention("m", 0, 0, 8, "Zuschüsse", "Zuschüsse",
                "zuschuß", MentionKind.NOUN, false, Set.of());
        assertThat(GlossaryDerivation.keyOf(mention)).as("Umlaut-Mehrzahl bleibt bei der Grundform")
                .isEqualTo("zuschuß");
    }

    @Test
    void rulesClassifyByHeadAndShape() {
        assertThat(GlossaryDerivation.ruleFor("Antragsdatenbank")).isEqualTo(CategoryRule.TECHNICAL_HEAD);
        assertThat(GlossaryDerivation.ruleFor("Load Balancer")).isEqualTo(CategoryRule.TECHNICAL_HEAD);
        assertThat(GlossaryDerivation.ruleFor("REST-API")).isEqualTo(CategoryRule.TECHNICAL_HEAD);
        assertThat(GlossaryDerivation.ruleFor("System")).isEqualTo(CategoryRule.TECHNICAL_HEAD);
        assertThat(GlossaryDerivation.ruleFor("SharePoint")).isEqualTo(CategoryRule.TECHNICAL_SHAPE);
        assertThat(GlossaryDerivation.ruleFor("antrag.yaml")).isEqualTo(CategoryRule.TECHNICAL_SHAPE);
        assertThat(GlossaryDerivation.ruleFor("GmbH")).isEqualTo(CategoryRule.NONE);
        assertThat(GlossaryDerivation.ruleFor("BAföG")).isEqualTo(CategoryRule.NONE);
        assertThat(GlossaryDerivation.ruleFor("Schnittstelle")).as("technisch vor Stelle")
                .isEqualTo(CategoryRule.TECHNICAL_HEAD);
        assertThat(GlossaryDerivation.ruleFor("Wirtschaftsprüfer")).isEqualTo(CategoryRule.ROLE_HEAD);
        assertThat(GlossaryDerivation.ruleFor("Sachbearbeiterin")).isEqualTo(CategoryRule.ROLE_HEAD);
        assertThat(GlossaryDerivation.ruleFor("Stelle")).isEqualTo(CategoryRule.ROLE_HEAD);
        // trügerische Wortenden: kurze und mehrdeutige Köpfe nie als Ende eines zusammengeschriebenen Worts
        for (String word : List.of("Import", "Support", "Rechtssystem", "Partnernetzwerk", "Aktienindex",
                "Rohrleitung", "Leerstelle", "Fundstelle", "Urkunde", "Halbleiter", "Ehrenamt", "Samt",
                "Studienmodul", "Leistungsverzeichnis", "Wärmespeicher")) {
            assertThat(GlossaryDerivation.ruleFor(word)).as(word).isEqualTo(CategoryRule.NONE);
        }
        assertThat(GlossaryDerivation.categoryOf(CategoryRule.NONE)).isEqualTo(Category.UNKLAR);
    }

    @Test
    void itCanBeCancelled() {
        List<KnowledgeGraph.AnalysedPage> pages = List.of(new PageBuilder("1").build(PageStatements.State.CURRENT));
        assertThatThrownBy(() -> GlossaryDerivation.of(pages, () -> true)).isInstanceOf(CancellationException.class);
    }

    /** Laufzeit an erzeugten Analysen (Zahl für den PR): 5000 Seiten mit je 40 Aussagen. */
    @Test
    void severalThousandPagesAreDerivedQuickly() {
        String[] verbs = {"prüfen", "senden", "genehmigen", "übermitteln", "erfassen", "buchen", "zahlen", "ablehnen"};
        String[] nouns = {"Antrag", "Bescheid", "Zuschuss", "Bewilligungsstelle", "Kunde", "Rechnung", "Vertrag",
                "Lieferung", "Prüfbericht", "Antragssystem"};
        List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
        for (int p = 0; p < 5000; p++) {
            PageBuilder page = new PageBuilder(String.valueOf(p));
            for (int i = 0; i < 40; i++) {
                int s = page.sentence(50);
                page.verb(s, verbs[(p + i) % verbs.length],
                        sure(Role.ACTOR, page.noun(s, nouns[(p + i) % nouns.length] + (i % 7))),
                        sure(Role.AFFECTED_OBJECT, page.noun(s, nouns[(p * 3 + i) % nouns.length])));
            }
            pages.add(page.build(PageStatements.State.CURRENT));
        }
        long start = System.nanoTime();
        GlossaryDerivation.Result result = GlossaryDerivation.of(pages, () -> false);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("FL5a Laufzeit: 5000 Seiten, 200000 Aussagen, " + result.terms().size() + " Begriffe in "
                + millis + " ms");
        assertThat(result.statements()).isEqualTo(200_000);
        assertThat(millis).isLessThan(30_000);
    }
}
