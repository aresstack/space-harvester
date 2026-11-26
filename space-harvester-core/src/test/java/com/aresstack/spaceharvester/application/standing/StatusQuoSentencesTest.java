package com.aresstack.spaceharvester.application.standing;

import static com.aresstack.spaceharvester.application.standing.StandingFixtures.features;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Negation;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Modality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Temporality;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Wortlaut der Aussagen für den sichtbaren Ausschnitt (ADR 0032, FL6), mit erfundenen Seiten. */
class StatusQuoSentencesTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("desktop");
    private static final AccessContext GUEST = AccessContext.technicalClient("gast");
    private static final String TEXT = "Die Kasse zahlt den Zuschuss. Die Kasse zahlt den Zuschuss nicht.";

    private final List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
    private final Map<String, String> texts = new HashMap<>();
    private final SentenceSource source = new SentenceSource() {
        @Override
        public Optional<KnowledgeGraph.AnalysedPage> analysis(AccessContext caller, KnowledgeGraph.PageRef page) {
            return pages.stream().filter(candidate -> candidate.page().key().equals(page.key())).findFirst();
        }

        @Override
        public Optional<String> text(KnowledgeGraph.PageRef page, String inputSha256) {
            return Optional.ofNullable(texts.get(page.key().pageId() + "@" + inputSha256));
        }
    };
    private final StatusQuo statusQuo = new StatusQuo(caller -> List.copyOf(pages), key -> Optional.empty(),
            StandingDecisionStore.inMemory(), new AccessGuard((context, scope, kind) ->
            kind == AccessKind.CONTENT && "gast".equals(context.principal()) ? AccessDecision.DENY
                    : AccessDecision.ALLOW), () -> StandingWords.DEFAULT,
            Clock.fixed(StandingFixtures.AT, ZoneOffset.UTC), source);

    private StandingFixtures.PageBuilder twoSentences(String pageId, char hash) {
        StandingFixtures.PageBuilder builder = page(pageId, "Förderung", hash)
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .action("Kasse", "zahlt", "Zuschuss", features(Negation.NEGATED, Modality.NONE, Temporality.GILT));
        builder.sentences.add(new ParsedStatements.Sentence(0, 0, 29));
        builder.sentences.add(new ParsedStatements.Sentence(1, 30, TEXT.length()));
        return builder;
    }

    @Test
    void theOriginalSentenceComesFromTheMatchingReaderTextForBothSidesOfAPair() {
        StandingFixtures.PageBuilder builder = twoSentences("1", 'a');
        pages.add(builder.build());
        texts.put("1@" + builder.input, TEXT);
        StatusQuoView view = statusQuo.view(EDITOR, key -> true);
        StatementConflicts.PossibleContradiction pair = StatementConflicts.of(view).contradictions().getFirst();

        Map<String, SentenceText> sentences = statusQuo.sentences(EDITOR,
                List.of(pair.affirmed().getFirst(), pair.negated().getFirst()));

        assertThat(sentences.get(pair.affirmed().getFirst().statement().id())).satisfies(text -> {
            assertThat(text.original()).isEqualTo("Die Kasse zahlt den Zuschuss.");
            assertThat(text.sentenceStart()).isZero();
            assertThat(text.sentenceEnd()).isEqualTo(29);
            assertThat(text.stale()).isFalse();
            assertThat(text.simplified()).isNull();
        });
        assertThat(sentences.get(pair.negated().getFirst().statement().id()).original())
                .isEqualTo("Die Kasse zahlt den Zuschuss nicht.");
    }

    @Test
    void anotherReaderTextGivesNoSentenceAndNoRightGivesNoEntry() {
        StandingFixtures.PageBuilder builder = twoSentences("1", 'a');
        pages.add(builder.build());
        texts.put("1@sha256:anders", TEXT);
        List<StatusQuoEntry> entries = statusQuo.view(EDITOR, key -> true).entries();

        assertThat(statusQuo.sentences(EDITOR, entries).values()).allSatisfy(text -> {
            assertThat(text.original()).isNull();
            assertThat(text.stale()).isTrue();
        });
        assertThat(statusQuo.sentences(GUEST, entries)).isEmpty();
    }

    @Test
    void theSimplifiedSentenceIsReadFromTheStatement() {
        StandingFixtures.PageBuilder builder = page("1", "Förderung", 'a')
                .add("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT), false,
                        ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Review.UNREVIEWED,
                        ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.FACT,
                                "Die Kasse zahlt den Zuschuss."));
        pages.add(builder.build());

        assertThat(statusQuo.sentences(EDITOR, statusQuo.view(EDITOR, key -> true).entries()).values())
                .singleElement().satisfies(text -> {
                    assertThat(text.simplified()).isEqualTo("Die Kasse zahlt den Zuschuss.");
                    assertThat(text.sentenceStart()).isEqualTo(-1);
                });
    }

    @Test
    void aWindowOfTwoHundredEntriesIsQuick() {
        for (int p = 0; p < 100; p++) {
            StandingFixtures.PageBuilder builder = twoSentences(Integer.toString(p), 'a');
            pages.add(builder.build());
            texts.put(p + "@" + builder.input, TEXT);
        }
        List<StatusQuoEntry> window = statusQuo.view(EDITOR, key -> true).entries();
        assertThat(window).hasSize(200);
        statusQuo.sentences(EDITOR, window);

        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            assertThat(statusQuo.sentences(EDITOR, window)).hasSize(200);
            best = Math.min(best, System.nanoTime() - start);
        }
        System.out.println("Wortlaut für 200 Einträge: " + best / 1_000 + " µs");
        assertThat(best / 1_000_000).as("großzügige Obergrenze").isLessThan(5_000);
    }

    @Test
    void aProgramStatementShowsItsHypothesisAndPointsAtItsCodeSiteNeverAtReaderTextOffsets() {
        String code = "       IDENTIFICATION DIVISION.\n       PROCEDURE DIVISION.\n       IF KZ-STATUS = 'A'\n"
                + "           PERFORM ABSCHLUSS.\n";
        StandingFixtures.PageBuilder builder = page("ANTRAG01", "ANTRAG01", 'c');
        var program = com.aresstack.spaceharvester.application.assets.DocumentKey.page(builder.key);
        var evidence = new com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence(program, "p1",
                List.of(new com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence.CodeSite(
                        program, "p1", 3, 4)), List.of(), List.of(), "cobol-program@5");
        builder.add("Sachbearbeiter", "prüft", "Antrag", features(Temporality.GILT), false,
                ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Review.UNREVIEWED,
                ParsedStatements.Derivation.programm("Der Sachbearbeiter prüft den Antrag.", evidence));
        builder.sentences.add(new ParsedStatements.Sentence(0, 0, 36));
        KnowledgeGraph.AnalysedPage analysed = builder.build();
        pages.add(analysed);
        texts.put("ANTRAG01@" + builder.input, code);

        SentenceText text = statusQuo.statementSentences(EDITOR, Map.of(analysed.page(),
                analysed.analysis().result().statements())).get(builder.statements.getFirst().id());

        assertThat(text.original()).isNull();
        assertThat(text.simplified()).isEqualTo("Der Sachbearbeiter prüft den Antrag.");
        assertThat(code.substring(text.sentenceStart(), text.sentenceEnd()))
                .isEqualTo("       IF KZ-STATUS = 'A'\n           PERFORM ABSCHLUSS.");
        assertThat(text.stale()).isFalse();
    }
}
