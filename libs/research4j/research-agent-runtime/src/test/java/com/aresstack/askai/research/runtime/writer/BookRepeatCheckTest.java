package com.aresstack.askai.research.runtime.writer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Component 12: the repeat check over the whole book, with a fake sentence model. */
public class BookRepeatCheckTest {

    private static final String SENSORS = "Sensoren in Arbeitskleidung messen die Körperhaltung der Beschäftigten in "
            + "Echtzeit [1].";
    /** The same statement in other words: one content stem in common with {@link #SENSORS}. */
    private static final String PARAPHRASE = "Die Haltung der Arbeiter wird von Kleidungssensoren laufend erfasst und "
            + "ausgewertet [3].";
    /** Cites the passage of {@link #SENSORS} and says something else. */
    private static final String SAME_EVIDENCE = "Die erfassten Daten unterliegen nach der DSGVO einer strengen "
            + "Zweckbindung [1].";
    /** Six of the seven content words of {@link #SENSORS}. */
    private static final String NEAR_COPY = "Sensoren in der Arbeitskleidung messen die Körperhaltung aller "
            + "Beschäftigten in Echtzeit [2].";
    private static final String TWENTY = "Wearables senken die Zahl der schweren Arbeitsunfälle auf großen "
            + "Baustellen um 20 % [1].";
    private static final String FORTY = "Auf großen Baustellen verringern Wearables die schweren Arbeitsunfälle "
            + "sogar um 40 % [2].";

    /** A sentence model with chosen vectors; every other text gets an axis of its own (cosine 0 to all). */
    static final class FakeModel implements SentenceVectors {
        final Map<String, float[]> known = new HashMap<String, float[]>();
        final List<List<String>> calls = new ArrayList<List<String>>();
        final Map<String, float[]> axes = new HashMap<String, float[]>();
        int failures;
        boolean shortAnswer;

        FakeModel with(String sentence, float... vector) {
            known.put(plain(sentence), Arrays.copyOf(vector, 16));
            return this;
        }

        public List<float[]> embed(List<String> sentences) throws Exception {
            calls.add(new ArrayList<String>(sentences));
            if (failures > 0) {
                failures--;
                throw new java.io.IOException("model down");
            }
            List<float[]> out = new ArrayList<float[]>();
            for (String s : sentences) {
                float[] v = known.get(s);
                if (v == null) {
                    v = axes.get(s);
                }
                if (v == null) {
                    v = new float[16];
                    v[4 + axes.size()] = 1;
                    axes.put(s, v);
                }
                out.add(v);
            }
            return shortAnswer ? out.subList(0, out.size() - 1) : out;
        }
    }

    static String plain(String sentence) {
        return new BookRepeatCheck.Sentence("", sentence, null).plainText();
    }

    static BookRepeatCheck.Sentence s(String section, String text, String... passages) {
        return new BookRepeatCheck.Sentence(section, text, Arrays.asList(passages));
    }

    private static BookRepeatCheck check(SentenceVectors model) {
        return new BookRepeatCheck(null, null, model, null, null);
    }

    @Test
    public void theSameStatementInOtherWordsIsARepeatByMeaning() {
        FakeModel model = new FakeModel().with(SENSORS, 1, 0).with(PARAPHRASE, 0.96f, 0.28f);
        BookRepeatCheck.Result result = check(model).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-b", PARAPHRASE, "p3")));

        assertEquals(BookRepeatCheck.Status.COMPLETE, result.getStatus());
        assertEquals("", result.getProblem());
        assertEquals(1, result.getPairs().size());
        BookRepeatCheck.Pair pair = result.getPairs().get(0);
        assertEquals(BookRepeatCheck.Reason.MEANING, pair.getReason());
        assertEquals(0, pair.getEarlierIndex());
        assertEquals(1, pair.getLaterIndex());
        assertEquals(0.96, pair.getSimilarity(), 1e-4);
        assertEquals("one of seven content stems in common (arbei)", 1 / 7.0, pair.getShare(), 1e-9);
        assertTrue(pair.getSharedPassageIds().isEmpty());
        assertEquals(1, pair.getMatches());
        assertEquals(2, result.getChecked());
        assertEquals("the later sentence's words", 11, result.getRepeatedWords());
        assertEquals(10 + 11, result.getWords());

        BookRepeatCheck.Result lexical = check(null).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-b", PARAPHRASE, "p3")));
        assertTrue("the section writer's word rule cannot see it", lexical.getPairs().isEmpty());
    }

    @Test
    public void citingTheSamePassageForAnotherStatementIsNoRepeat() {
        FakeModel model = new FakeModel().with(SENSORS, 1, 0).with(SAME_EVIDENCE, 0.6f, 0.8f);
        BookRepeatCheck.Result result = check(model).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-b", SAME_EVIDENCE, "p1")));

        assertTrue(result.getPairs().isEmpty());
        assertEquals(0, result.getRepeatedWords());
        BookRepeatCheck.Pair nearest = result.getNearest().get(0);
        assertNull(nearest.getReason());
        assertEquals(0, nearest.getMatches());
        assertEquals("reported, never a reason", Collections.singletonList("p1"), nearest.getSharedPassageIds());
        assertEquals(0.6, nearest.getSimilarity(), 1e-4);
    }

    @Test
    public void aNearCopyIsARepeatByWordsEvenWithoutTheModel() {
        BookRepeatCheck.Result result = check(null).check(Arrays.asList(
                s("sec-a", SENSORS, "p1"), s("sec-c", NEAR_COPY, "p2")));

        assertEquals(1, result.getPairs().size());
        BookRepeatCheck.Pair pair = result.getPairs().get(0);
        assertEquals(BookRepeatCheck.Reason.WORDS, pair.getReason());
        assertEquals(6 / 7.0, pair.getShare(), 1e-9);
        assertEquals(-1, pair.getSimilarity(), 0);
        assertEquals("the meaning rule is on and had no model", BookRepeatCheck.Status.INCOMPLETE,
                result.getStatus());
        assertEquals("no sentence model: only the word rules ran", result.getProblem());
        assertEquals(2, result.getWithoutVector());

        BookRepeatCheck wordsOnly = new BookRepeatCheck(new RepeatCheck.Criteria(6, 0.75, 0), null, null, null, null);
        BookRepeatCheck.Result off = wordsOnly.check(Arrays.asList(s("sec-a", SENSORS), s("sec-c", NEAR_COPY)));
        assertEquals("the meaning rule switched off is no gap", BookRepeatCheck.Status.COMPLETE, off.getStatus());
        assertEquals(0, off.getWithoutVector());
        assertEquals(1, off.getPairs().size());
    }

    @Test
    public void aNewNumberIsAnotherStatement() {
        FakeModel model = new FakeModel().with(TWENTY, 1, 0).with(FORTY, 1, 0);
        BookRepeatCheck.Result result = check(model).check(Arrays.asList(s("sec-a", TWENTY), s("sec-b", FORTY)));

        assertTrue(result.getPairs().isEmpty());
        BookRepeatCheck.Pair nearest = result.getNearest().get(0);
        assertEquals(1.0, nearest.getSimilarity(), 1e-6);
        assertEquals(false, nearest.isNumbersCovered());
        assertNull(nearest.getReason());

        String withoutNumber = "Wearables verringern auf Baustellen die Zahl der Arbeitsunfälle deutlich.";
        BookRepeatCheck.Result back = check(model.with(withoutNumber, 1, 0)).check(Arrays.asList(s("sec-a", FORTY),
                s("sec-b", withoutNumber)));
        assertEquals("a sentence without a number may repeat one with a number", 1, back.getPairs().size());
        assertEquals(BookRepeatCheck.Reason.MEANING, back.getPairs().get(0).getReason());
    }

    @Test
    public void onlyEarlierSectionsUnlessTheOptionsWiden() {
        FakeModel model = new FakeModel().with(SENSORS, 1, 0).with(PARAPHRASE, 0.96f, 0.28f);
        List<BookRepeatCheck.Sentence> book = Arrays.asList(s("sec-a", SENSORS), s("sec-a", PARAPHRASE));

        assertTrue(check(model).check(book).getPairs().isEmpty());
        assertTrue("nothing to compare with", check(model).check(book).getNearest().isEmpty());
        BookRepeatCheck within = new BookRepeatCheck(null, null, model,
                new BookRepeatCheck.Options(true, 32, 2), null);
        assertEquals(1, within.check(book).getPairs().size());
    }

    @Test
    public void theSameTextTwiceIsARepeatWhateverItsLength() {
        BookRepeatCheck.Result result = check(new FakeModel()).check(Arrays.asList(
                s("sec-a", "Das ist wichtig [1]."), s("sec-b", "Das ist  wichtig."), s("sec-c", "Das ist neu.")));

        assertEquals(1, result.getPairs().size());
        assertEquals(BookRepeatCheck.Reason.SAME_TEXT, result.getPairs().get(0).getReason());
        assertEquals(1, result.getPairs().get(0).getLaterIndex());
        assertEquals("too few content words for the word and meaning rules", 0, result.getChecked());
        assertTrue(result.getNearest().isEmpty());
    }

    @Test
    public void thePairNamesTheMostSimilarEarlierSentence() {
        String first = "Datenbrillen zeigen Montageanleitungen direkt im Blickfeld der Monteure an.";
        String second = "Smarte Brillen blenden Arbeitsanweisungen unmittelbar im Sichtfeld der Monteure ein.";
        String later = "Monteure sehen die Anleitung zur Montage über ihre Datenbrille direkt vor Augen.";
        FakeModel model = new FakeModel().with(first, 0.95f, 0.3122f).with(second, 0.97f, 0.2431f).with(later, 1, 0);
        BookRepeatCheck.Result result = check(model).check(Arrays.asList(
                s("sec-a", first), s("sec-b", second), s("sec-c", later)));

        assertEquals("the second repeats the first as well (cosine 0.998)", 2, result.getPairs().size());
        BookRepeatCheck.Pair pair = result.getPairs().get(1);
        assertEquals(2, pair.getLaterIndex());
        assertEquals(1, pair.getEarlierIndex());
        assertEquals(0.97, pair.getSimilarity(), 1e-3);
        assertEquals(2, pair.getMatches());
        assertEquals(pair.getEarlierIndex(), result.getNearest().get(1).getEarlierIndex());
        assertEquals(BookRepeatCheck.Reason.MEANING, result.getNearest().get(1).getReason());
    }

    @Test
    public void aFailedBatchGetsOneRepairAttempt() {
        FakeModel model = new FakeModel().with(SENSORS, 1, 0).with(PARAPHRASE, 0.96f, 0.28f);
        model.failures = 1;
        BookRepeatCheck.Result result = check(model).check(Arrays.asList(
                s("sec-a", SENSORS), s("sec-b", PARAPHRASE)));

        assertEquals(BookRepeatCheck.Status.COMPLETE, result.getStatus());
        assertEquals(2, model.calls.size());
        assertEquals(1, result.getPairs().size());
    }

    @Test
    public void aModelThatStaysDownLeavesTheCheckIncompleteNotClean() {
        FakeModel model = new FakeModel();
        model.failures = 100;
        List<String> lines = new ArrayList<String>();
        BookRepeatCheck check = new BookRepeatCheck(null, null, model, new BookRepeatCheck.Options(false, 1, 2),
                lines::add);
        BookRepeatCheck.Result result = check.check(Arrays.asList(
                s("sec-a", SENSORS), s("sec-b", PARAPHRASE), s("sec-c", NEAR_COPY)));

        assertEquals(BookRepeatCheck.Status.INCOMPLETE, result.getStatus());
        assertEquals("sentence model failed: model down", result.getProblem());
        assertEquals("two attempts on the first batch, then no further batch", 2, model.calls.size());
        assertEquals(3, result.getWithoutVector());
        assertEquals("what the word rule finds stays", 1, result.getPairs().size());
        assertEquals(BookRepeatCheck.Reason.WORDS, result.getPairs().get(0).getReason());
        assertTrue(lines.get(0).contains("batch 1, attempt 1 of 2: model down"));
        assertTrue(lines.get(lines.size() - 1).startsWith("repeat check: INCOMPLETE"));
    }

    @Test
    public void anAnswerWithTooFewVectorsIsAFailure() {
        FakeModel model = new FakeModel();
        model.shortAnswer = true;
        BookRepeatCheck.Result result = check(model).check(Arrays.asList(s("sec-a", SENSORS), s("sec-b", PARAPHRASE)));

        assertEquals(BookRepeatCheck.Status.INCOMPLETE, result.getStatus());
        assertEquals("sentence model failed: sentence model returned 1 vectors for 2 sentences", result.getProblem());
        assertEquals(2, model.calls.size());
    }

    @Test
    public void eachDistinctTextIsEmbeddedOnceInBatches() {
        FakeModel model = new FakeModel();
        BookRepeatCheck check = new BookRepeatCheck(null, null, model, new BookRepeatCheck.Options(false, 2, 2), null);
        check.check(Arrays.asList(s("sec-a", SENSORS), s("sec-b", SAME_EVIDENCE), s("sec-c", SENSORS),
                s("sec-d", TWENTY), s("sec-e", FORTY), s("sec-e", "[4]")));

        assertEquals(2, model.calls.size());
        assertEquals(Arrays.asList(plain(SENSORS), plain(SAME_EVIDENCE)), model.calls.get(0));
        assertEquals(Arrays.asList(plain(TWENTY), plain(FORTY)), model.calls.get(1));
    }

    @Test
    public void sentencesOfASectionCarryTheirPassages() {
        Map<Integer, String> passages = new HashMap<Integer, String>();
        passages.put(1, "p1");
        passages.put(2, "p2");
        List<BookRepeatCheck.Sentence> sentences = BookRepeatCheck.sentencesOf("sec-a",
                "Helme schützen den Kopf [1]. Westen machen sichtbar. [2]\n\nSensoren messen [1, 3].", passages);

        assertEquals(3, sentences.size());
        assertEquals("Helme schützen den Kopf [1].", sentences.get(0).getText());
        assertEquals(Collections.singletonList("p1"), sentences.get(0).getPassageIds());
        assertEquals("Westen machen sichtbar. [2]", sentences.get(1).getText());
        assertEquals(Collections.singletonList("p2"), sentences.get(1).getPassageIds());
        assertEquals("a marker without a passage is left out", Collections.singletonList("p1"),
                sentences.get(2).getPassageIds());
        assertEquals("sec-a", sentences.get(2).getSectionId());
        assertEquals("Sensoren messen.", sentences.get(2).plainText());
    }
}
