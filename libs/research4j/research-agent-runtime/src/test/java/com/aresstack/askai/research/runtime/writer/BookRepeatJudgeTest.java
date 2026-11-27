package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aresstack.askai.research.runtime.writer.BookRepeatCheckTest.s;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Component 12 with the model stage: the rules pick candidate pairs, the judge decides. */
public class BookRepeatJudgeTest {

    private static final String SENSORS = "Sensoren in Arbeitskleidung messen die Körperhaltung der Beschäftigten in "
            + "Echtzeit [1].";
    private static final String PARAPHRASE = "Die Haltung der Arbeiter wird von Kleidungssensoren laufend erfasst und "
            + "ausgewertet [3].";
    private static final String NEAR_COPY = "Sensoren in der Arbeitskleidung messen die Körperhaltung aller "
            + "Beschäftigten in Echtzeit [2].";
    private static final String OTHER_ACTOR = "Versicherte dürfen freiwillig entscheiden, ob ihre Haltungsdaten "
            + "zusätzlich ausgewertet werden [4].";
    private static final String TWENTY = "Wearables senken die Zahl der schweren Arbeitsunfälle auf großen "
            + "Baustellen um 20 % [1].";
    private static final String FORTY = "Auf großen Baustellen verringern Wearables die schweren Arbeitsunfälle "
            + "sogar um 40 % [2].";

    /** A judge from a script: the first marker found in the prompt picks the verdict; thread-safe. */
    static final class Judge implements MainModelChat {
        final Map<String, String> byMarker = new LinkedHashMap<String, String>();
        final AtomicInteger calls = new AtomicInteger();
        final List<String> prompts = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean down;
        volatile CyclicBarrier barrier;

        Judge answer(String marker, String word) {
            byMarker.put(marker, word);
            return this;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            String prompt = messages.get(1).getContent();
            prompts.add(prompt);
            CyclicBarrier wait = barrier;
            if (wait != null) {
                try {
                    wait.await(5, TimeUnit.SECONDS);
                } catch (Exception notParallel) {
                    return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "not parallel");
                }
            }
            if (down) {
                return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "model down");
            }
            for (Map.Entry<String, String> entry : byMarker.entrySet()) {
                if (prompt.contains(entry.getKey())) {
                    return MainModelChatResult.ok(SameStatementJudgeTest.verdict(entry.getValue(),
                            "Grund zu " + entry.getKey() + "."));
                }
            }
            return MainModelChatResult.ok(SameStatementJudgeTest.verdict("VERSCHIEDEN", "Kein Treffer."));
        }

        @Override
        public String modelName() {
            return "scripted-judge";
        }
    }

    private static BookRepeatCheck check(SentenceVectors vectors, Judge judge, BookRepeatCheck.Options options) {
        return new BookRepeatCheck(null, null, vectors, new SameStatementJudge(judge, "de"), options, null);
    }

    private static BookRepeatCheck check(SentenceVectors vectors, Judge judge) {
        return check(vectors, judge, null);
    }

    private static float[] axis(int index, double weight, int second, double secondWeight) {
        float[] v = new float[16];
        v[index] = (float) weight;
        if (second >= 0) {
            v[second] = (float) secondWeight;
        }
        return v;
    }

    /** {@code n} pairs, each on two axes of its own: earlier k (sec-a) and later k (sec-b) at cosine 0.9. */
    private static List<BookRepeatCheck.Sentence> separatePairs(BookRepeatCheckTest.FakeModel model, int n) {
        String[] names = {"Alpha", "Bravo", "Charlie", "Delta", "Echo"};
        List<BookRepeatCheck.Sentence> book = new ArrayList<BookRepeatCheck.Sentence>();
        List<BookRepeatCheck.Sentence> later = new ArrayList<BookRepeatCheck.Sentence>();
        for (int k = 0; k < n; k++) {
            String e = "Projekt " + names[k] + " beschreibt tragbare Sensoren für schwere Arbeiten im Hochbau.";
            String l = "Im Hochbau nutzt Projekt " + names[k] + "x tragbare Sensoren bei besonders schweren "
                    + "Arbeiten.";
            model.with(e, axis(2 * k, 1, -1, 0));
            model.with(l, axis(2 * k, 0.9, 2 * k + 1, Math.sqrt(1 - 0.81)));
            book.add(s("sec-a", e));
            later.add(s("sec-b", l));
        }
        book.addAll(later);
        return book;
    }

    @Test
    public void aCandidateTheJudgeCallsGleichIsReported() {
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel()
                .with(SENSORS, 1, 0).with(PARAPHRASE, 0.96f, 0.28f);
        Judge judge = new Judge().answer("Kleidungssensoren", "GLEICH");

        BookRepeatCheck.Result result = check(model, judge).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-b", PARAPHRASE, "p3")));

        assertEquals(BookRepeatCheck.Status.COMPLETE, result.getStatus());
        assertEquals(1, judge.calls.get());
        assertTrue("the judge reads the sentences without markers",
                judge.prompts.get(0).contains("Früherer Satz:\nSensoren in Arbeitskleidung messen die Körperhaltung "
                        + "der Beschäftigten in Echtzeit.\n"));
        assertEquals(1, result.getPairs().size());
        BookRepeatCheck.Pair pair = result.getPairs().get(0);
        assertEquals(BookRepeatCheck.Reason.SAME_STATEMENT, pair.getReason());
        assertEquals(0.96, pair.getSimilarity(), 1e-4);
        assertEquals(1, pair.getMatches());
        assertEquals(1, result.getJudged().size());
        BookRepeatCheck.Judged judged = result.getJudged().get(0);
        assertTrue(judged.isReported());
        assertEquals(SameStatementJudge.Verdict.SAME, judged.getVerdict());
        assertEquals("Grund zu Kleidungssensoren.", judged.getJudgement().getReason());
        assertEquals(SameStatementJudgeTest.verdict("GLEICH", "Grund zu Kleidungssensoren."),
                judged.getJudgement().getAnswers().get(0));
        assertEquals("scripted-judge", result.getJudgeModel());
        assertEquals(1, result.count(SameStatementJudge.Verdict.SAME));
    }

    @Test
    public void teilweiseIsAHintVerschiedenIsNothing() {
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel()
                .with(SENSORS, 1, 0).with(PARAPHRASE, 0.96f, 0.28f).with(OTHER_ACTOR, 0.9f, 0, 0.436f);
        Judge judge = new Judge().answer("Versicherte", "VERSCHIEDEN").answer("Kleidungssensoren", "TEILWEISE");

        BookRepeatCheck.Result result = check(model, judge).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-b", PARAPHRASE, "p3"), s("sec-c", OTHER_ACTOR, "p4")));

        assertTrue("no order to the writer", result.getPairs().isEmpty());
        assertEquals(0, result.getRepeatedWords());
        assertEquals(1, result.getHints().size());
        BookRepeatCheck.Pair hint = result.getHints().get(0);
        assertEquals(BookRepeatCheck.Reason.PARTLY_SAME, hint.getReason());
        assertEquals("PARAPHRASE partly repeats SENSORS", 1, hint.getLaterIndex());
        assertEquals(0, hint.getEarlierIndex());
        assertEquals(1, result.count(BookRepeatCheck.Reason.PARTLY_SAME));
        assertEquals(0, result.count(BookRepeatCheck.Reason.SAME_STATEMENT));
        assertEquals("PARAPHRASE vs SENSORS; OTHER_ACTOR vs SENSORS and vs PARAPHRASE (0.86)", 3,
                result.getJudged().size());
        assertEquals(1, result.count(SameStatementJudge.Verdict.PARTLY));
        assertEquals(2, result.count(SameStatementJudge.Verdict.DIFFERENT));
        for (BookRepeatCheck.Judged judged : result.getJudged()) {
            assertFalse(judged.isReported());
        }
        assertNull(result.getJudged().get(1).getPair().getReason());
        assertEquals(BookRepeatCheck.Status.COMPLETE, result.getStatus());
        assertTrue(result.toString(), result.toString().contains("0 of 3 sentences repeat, 1 hints TEILWEISE"));
    }

    @Test
    public void normReferencesAreNoNumbersForTheModelStage() {
        String earlier = "Arbeitgeber dürfen Daten der Beschäftigten für Zwecke des Beschäftigungsverhältnisses "
                + "verarbeiten [1].";
        String withNorm = "Nach § 26 Abs. 1 BDSG dürfen Arbeitgeber Daten der Beschäftigten für Zwecke des "
                + "Beschäftigungsverhältnisses verarbeiten [2].";
        String withShare = "Arbeitgeber dürfen Daten von 20 % der Beschäftigten für Zwecke des "
                + "Beschäftigungsverhältnisses verarbeiten [3].";
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel()
                .with(earlier, 1, 0).with(withNorm, 0.95f, 0.312f).with(withShare, 0.95f, 0, 0.312f);
        Judge judge = new Judge().answer("BDSG", "GLEICH").answer("20 %", "GLEICH");

        BookRepeatCheck.Result result = check(model, judge).check(Arrays.asList(
                s("sec-a", earlier), s("sec-b", withNorm), s("sec-c", withShare)));

        assertEquals("the § sentence is asked, the new share is not", 1, judge.calls.get());
        assertEquals(1, result.getPairs().size());
        assertEquals(1, result.getPairs().get(0).getLaterIndex());
        assertTrue(result.getPairs().get(0).isNumbersCovered());

        BookRepeatCheck.Result rulesOnly = new BookRepeatCheck(null, null, model, null, null).check(Arrays.asList(
                s("sec-a", earlier), s("sec-b", withNorm)));
        assertTrue("without a judge the norm numbers still count", rulesOnly.getPairs().isEmpty());
        assertFalse(rulesOnly.getNearest().get(0).isNumbersCovered());
    }

    @Test
    public void theNormReferencePattern() {
        String[] norms = {"§ 26 Abs. 1 BDSG", "§§ 87 und 90 BetrVG", "Art. 6 Abs. 1 lit. f DSGVO",
            "Artikel 9 Absatz 2", "Article 88(1) GDPR", "§ 5 ArbSchG", "den Artikeln 12 bis 22 und 34 DSGVO",
            "§ 87 Abs. 1 Nr. 6 BetrVG", "Paragraf 87 Absatz 1 Nummer 6", "Art. 4 Ziff. 15", "Articles 13 and 14"};
        for (String norm : norms) {
            assertTrue(norm, RepeatCheck.numbers(BookRepeatCheck.NORM_REFERENCE.matcher(norm).replaceAll(" § "))
                    .isEmpty());
        }
        assertEquals("a quantity after the reference stays", 1, RepeatCheck.numbers(BookRepeatCheck.NORM_REFERENCE
                .matcher("Nach § 26 BDSG gilt das für 20 % der Fälle.").replaceAll(" § ")).size());
    }

    @Test
    public void gleichGoesBeforeAMoreSimilarTeilweise() {
        String later = "Helmsensoren erkennen Stürze sofort und alarmieren automatisch die zuständigen Ersthelfer.";
        String[] names = {"Alpha", "Bravo", "Charlie"};
        double[] cosines = {0.95, 0.90, 0.85};
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel().with(later, 1, 0);
        List<BookRepeatCheck.Sentence> book = new ArrayList<BookRepeatCheck.Sentence>();
        for (int k = 0; k < names.length; k++) {
            String earlier = "Fassung " + names[k] + ": Sensoren im Schutzhelm melden jeden Sturz an Ersthelfer.";
            model.with(earlier, (float) cosines[k], (float) Math.sqrt(1 - cosines[k] * cosines[k]));
            book.add(s("sec-a", earlier));
        }
        book.add(s("sec-b", later));
        Judge judge = new Judge().answer("Alpha", "TEILWEISE").answer("Bravo", "GLEICH").answer("Charlie",
                "TEILWEISE");

        BookRepeatCheck.Result result = check(model, judge).check(book);

        assertEquals(1, result.getPairs().size());
        BookRepeatCheck.Pair pair = result.getPairs().get(0);
        assertEquals("the most similar GLEICH, not the more similar TEILWEISE", 1, pair.getEarlierIndex());
        assertEquals(BookRepeatCheck.Reason.SAME_STATEMENT, pair.getReason());
        assertEquals("three earlier sentences repeated, wholly or partly", 3, pair.getMatches());
        assertTrue(result.getRepeatedWords() > 0);
        assertTrue("a sentence with a GLEICH gets no hint", result.getHints().isEmpty());
        assertFalse(result.getJudged().get(0).isReported());
        assertEquals(BookRepeatCheck.Reason.PARTLY_SAME, result.getJudged().get(0).getPair().getReason());
        assertTrue(result.getJudged().get(1).isReported());
        assertFalse(result.getJudged().get(2).isReported());
    }

    @Test
    public void belowTheCandidateSimilarityOrWithANewNumberTheModelIsNotAsked() {
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel()
                .with(SENSORS, 1, 0).with(PARAPHRASE, 0.69f, 0.72f)
                .with(TWENTY, 0, 0, 1).with(FORTY, 0, 0, 0.98f, 0.2f);
        Judge judge = new Judge().answer("Kleidungssensoren", "GLEICH").answer("40", "GLEICH");

        BookRepeatCheck.Result result = check(model, judge).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-a", TWENTY, "p5"),
                s("sec-b", PARAPHRASE, "p3"), s("sec-b", FORTY, "p6")));

        assertEquals("0.69 is below 0.70; 40 % is a number the earlier sentence does not state", 0, judge.calls.get());
        assertTrue(result.getJudged().isEmpty());
        assertTrue(result.getPairs().isEmpty());

        BookRepeatCheck.Result wider = check(model, judge, BookRepeatCheck.Options.defaults().withJudging(0.6, 3, 4))
                .check(Arrays.asList(s("sec-a", SENSORS, "p1"), s("sec-b", PARAPHRASE, "p3")));
        assertEquals("a lower candidate similarity is a setting", 1, judge.calls.get());
        assertEquals(1, wider.getPairs().size());
    }

    @Test
    public void theSameTextIsReportedWithoutACall() {
        Judge judge = new Judge();
        BookRepeatCheck.Result result = check(new BookRepeatCheckTest.FakeModel(), judge).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-b", "Sensoren in Arbeitskleidung messen die Körperhaltung der "
                        + "Beschäftigten in Echtzeit [7].", "p7")));

        assertEquals(0, judge.calls.get());
        assertEquals(1, result.getPairs().size());
        assertEquals(BookRepeatCheck.Reason.SAME_TEXT, result.getPairs().get(0).getReason());
        assertTrue(result.getJudged().isEmpty());
    }

    @Test
    public void withoutASentenceModelTheWordRuleStillPicksCandidates() {
        Judge judge = new Judge().answer("aller", "GLEICH");

        BookRepeatCheck.Result result = check(null, judge).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-b", NEAR_COPY, "p2"), s("sec-c", PARAPHRASE, "p3")));

        assertEquals(BookRepeatCheck.Status.INCOMPLETE, result.getStatus());
        assertEquals("no sentence model: only the word rule picked candidates", result.getProblem());
        assertEquals("only the near copy reaches the word rule", 1, judge.calls.get());
        assertEquals(1, result.getPairs().size());
        assertEquals(BookRepeatCheck.Reason.SAME_STATEMENT, result.getPairs().get(0).getReason());
        assertEquals(-1, result.getPairs().get(0).getSimilarity(), 0);
    }

    @Test
    public void aPairTheJudgeCannotJudgeMakesTheResultIncomplete() {
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel()
                .with(SENSORS, 1, 0).with(PARAPHRASE, 0.96f, 0.28f);
        Judge judge = new Judge();
        judge.down = true;
        List<String> log = new ArrayList<String>();

        BookRepeatCheck.Result result = new BookRepeatCheck(null, null, model, new SameStatementJudge(judge, "de"),
                null, log::add).check(Arrays.asList(s("sec-a", SENSORS, "p1"), s("sec-b", PARAPHRASE, "p3")));

        assertEquals("the first call and one repair attempt", 2, judge.calls.get());
        assertEquals(BookRepeatCheck.Status.INCOMPLETE, result.getStatus());
        assertEquals("1 candidate pairs without a verdict: model call failed (TIMEOUT): model down",
                result.getProblem());
        assertTrue("an error is never \"no repeat\"", result.getPairs().isEmpty());
        assertEquals(SameStatementJudge.Verdict.ERROR, result.getJudged().get(0).getVerdict());
        assertTrue(log.toString(), log.get(0).startsWith("repeat check: judge failed on a pair"));
    }

    @Test
    public void afterThreeFailedPairsInARowTheRestIsNotAsked() {
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel();
        List<BookRepeatCheck.Sentence> book = separatePairs(model, 5);
        Judge judge = new Judge();
        judge.down = true;

        BookRepeatCheck.Result result = check(model, judge, BookRepeatCheck.Options.defaults().withJudging(0.7, 3, 1))
                .check(book);

        assertEquals("three pairs, two calls each", 6, judge.calls.get());
        assertEquals(3, result.count(SameStatementJudge.Verdict.ERROR));
        assertEquals(2, result.count(SameStatementJudge.Verdict.NOT_JUDGED));
        assertEquals("not asked: the judge failed 3 pairs in a row",
                result.getJudged().get(4).getJudgement().getProblem());
        assertEquals(BookRepeatCheck.Status.INCOMPLETE, result.getStatus());
        assertTrue(result.getProblem().startsWith("5 candidate pairs without a verdict"));
    }

    @Test
    public void onlyTheMostSimilarCandidatesOfASentenceAreJudged() {
        String later = "Helmsensoren erkennen Stürze sofort und alarmieren automatisch die zuständigen Ersthelfer.";
        String[] names = {"Alpha", "Bravo", "Charlie", "Delta", "Echo"};
        double[] cosines = {0.95, 0.90, 0.85, 0.80, 0.75};
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel().with(later, 1, 0);
        List<BookRepeatCheck.Sentence> book = new ArrayList<BookRepeatCheck.Sentence>();
        for (int k = 0; k < names.length; k++) {
            String earlier = "Fassung " + names[k] + ": Sensoren im Schutzhelm melden jeden Sturz an Ersthelfer.";
            model.with(earlier, (float) cosines[k], (float) Math.sqrt(1 - cosines[k] * cosines[k]));
            book.add(s("sec-a", earlier));
        }
        book.add(s("sec-b", later));
        Judge judge = new Judge().answer("Alpha", "VERSCHIEDEN").answer("Bravo", "GLEICH").answer("Charlie", "GLEICH")
                .answer("Delta", "GLEICH");

        BookRepeatCheck.Result result = check(model, judge).check(book);

        assertEquals("the three most similar of five", 3, judge.calls.get());
        assertEquals(3, result.getJudged().size());
        assertEquals("Alpha first", 0, result.getJudged().get(0).getPair().getEarlierIndex());
        BookRepeatCheck.Pair pair = result.getPairs().get(0);
        assertEquals("the most similar GLEICH: Bravo", 1, pair.getEarlierIndex());
        assertEquals(2, pair.getMatches());
        assertTrue(result.getJudged().get(1).isReported());
        assertFalse(result.getJudged().get(2).isReported());
    }

    @Test
    public void eachPairOfTextsIsJudgedOnce() {
        String a = "Tragbare Sensoren erfassen Herzfrequenz und Körpertemperatur während anstrengender Arbeiten.";
        String b = "Während anstrengender Arbeiten messen getragene Geräte Puls und Temperatur des Körpers.";
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel().with(a, 1, 0).with(b, 0.9f, 0.436f);
        Judge judge = new Judge().answer("Puls", "GLEICH");

        BookRepeatCheck.Result result = check(model, judge).check(Arrays.asList(
                s("sec-a", a), s("sec-c", a), s("sec-d", b)));

        assertEquals(1, judge.calls.get());
        assertEquals(2, result.getPairs().size());
        assertEquals(BookRepeatCheck.Reason.SAME_TEXT, result.getPairs().get(0).getReason());
        assertEquals(BookRepeatCheck.Reason.SAME_STATEMENT, result.getPairs().get(1).getReason());
        assertEquals("the earlier of two equal candidates", 0, result.getPairs().get(1).getEarlierIndex());
        assertEquals(2, result.getJudged().size());
        assertTrue(result.getJudged().get(0).getJudgement() == result.getJudged().get(1).getJudgement());
    }

    @Test
    public void theJudgeRunsInParallel() {
        BookRepeatCheckTest.FakeModel model = new BookRepeatCheckTest.FakeModel();
        List<BookRepeatCheck.Sentence> book = separatePairs(model, 4);
        Judge judge = new Judge().answer("Projekt", "GLEICH");
        judge.barrier = new CyclicBarrier(4);

        BookRepeatCheck.Result result = check(model, judge).check(book);

        assertEquals("four calls waited for each other", 4, judge.calls.get());
        assertEquals(4, result.count(SameStatementJudge.Verdict.SAME));
        assertEquals(4, result.getPairs().size());
        assertEquals(BookRepeatCheck.Status.COMPLETE, result.getStatus());
        for (int k = 0; k < 4; k++) {
            assertEquals("in reading order", 4 + k, result.getPairs().get(k).getLaterIndex());
        }
    }
}
