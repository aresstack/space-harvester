package com.aresstack.writing4j.sentencecheck;

import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Attribution;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Jurisdiction;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Kind;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Verdict;
import com.aresstack.knowledge4j.file.CorruptFileException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static com.aresstack.writing4j.sentencecheck.SentenceVerdictTest.PASSAGE;
import static com.aresstack.writing4j.sentencecheck.SentenceVerdictTest.QUOTE;
import static com.aresstack.writing4j.sentencecheck.SentenceVerdictTest.SENTENCE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The sentence check's verdicts on disk: append only, the latest per sentence and passage holds, fail closed. */
public class SentenceVerdictsTest {

    static final String OTHER = "Radfahrer dürfen in Fahrradstraßen nebeneinander fahren [4].";

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    static SentenceVerdict covered(String sentence) {
        return SentenceVerdict.checked("S1", sentence, "p1", "s1", PASSAGE, Verdict.GEDECKT, Kind.TEXT, QUOTE,
                "Die Passage nennt den Rückgang.", Attribution.NICHT_GEFRAGT, Jurisdiction.NICHT_GEFRAGT,
                "gemma4:e2b", SentenceCheck.VERSION, 10L, 5L);
    }

    static SentenceVerdict notCovered(String sentence) {
        return SentenceVerdict.checked("S2", sentence, "p1", "s1", PASSAGE, Verdict.NICHT_GEDECKT, Kind.TEXT, "",
                "Die Passage spricht von Tempo 30, nicht von Radfahrern.", Attribution.ZUGESCHRIEBEN,
                Jurisdiction.KEINE_RECHTSAUSSAGE, "gemma4:e2b", SentenceCheck.VERSION, 20L, 6L);
    }

    @Test
    public void theLatestVerdictOfASentenceAndPassageHoldsAcrossOpenings() throws Exception {
        File project = scratch.newFolder("projekt");
        SentenceVerdicts verdicts = new SentenceVerdicts(project);
        assertEquals(0, verdicts.open());
        verdicts.record(SentenceVerdict.failed("S1", SENTENCE, "p1", "s1", PASSAGE, "model call failed", "gemma4:e2b",
                SentenceCheck.VERSION, 5L, 1L), Arrays.asList("kein JSON", ""));
        verdicts.record(covered(SENTENCE), Arrays.asList("{\"urteil\":\"GEDECKT\"}"));
        verdicts.record(notCovered(OTHER));

        SentenceVerdicts again = new SentenceVerdicts(project);
        assertEquals(3, again.open());
        assertEquals(2, again.size());
        String key = SentenceVerdict.sentenceKey("S1", SENTENCE);
        String text = SentenceVerdict.textKey("p1", PASSAGE);
        SentenceVerdict read = again.verdict(key, text);
        assertEquals("the later GEDECKT holds", Verdict.GEDECKT, read.getVerdict());
        assertEquals(Verdict.GEDECKT, read.getSaid());
        assertEquals(QUOTE, read.getQuote());
        assertEquals(Kind.TEXT, read.getKind());
        assertEquals(5L, read.getMillis());
        SentenceVerdict other = again.verdict(SentenceVerdict.sentenceKey("S2", OTHER), text);
        assertEquals(Attribution.ZUGESCHRIEBEN, other.getAttribution());
        assertEquals(Jurisdiction.KEINE_RECHTSAUSSAGE, other.getJurisdiction());
        assertTrue(other.needsRewrite());
        assertEquals(1, again.ofSentence(key).size());
        assertNull(again.verdict(key, "anderer text"));

        List<String> lines = Files.readAllLines(verdicts.logFile().toPath(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("urteil", first.get("art").getAsString());
        assertEquals(1, first.get("v").getAsInt());
        assertEquals("FEHLER", first.get("status").getAsString());
        assertEquals("", first.get("urteil").getAsString());
        assertEquals("kein JSON", first.getAsJsonArray("rohantworten").get(0).getAsString());
        assertEquals(new File(new File(project, "sentence-check"), "verdicts.jsonl"), verdicts.logFile());
    }

    @Test
    public void aLineThatCannotBeReadFailsClosedAnUnfinishedLastLineIsCut() throws Exception {
        File project = scratch.newFolder("projekt");
        SentenceVerdicts verdicts = new SentenceVerdicts(project);
        verdicts.open();
        verdicts.record(covered(SENTENCE));
        File file = verdicts.logFile();
        String good = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        Files.write(file.toPath(), (good + "{\"v\":1,\"art\":\"urteil\",\"satz\":\"halb")
                .getBytes(StandardCharsets.UTF_8));
        SentenceVerdicts cut = new SentenceVerdicts(project);
        assertEquals(1, cut.open());
        assertEquals(good, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));

        String okButNoVerdict = good.trim().replace("\"urteil\":\"GEDECKT\"", "\"urteil\":\"\"") + "\n";
        for (String bad : new String[]{"kaputt\n", "{\"v\":1,\"art\":\"marke\"}\n",
                good.trim().replace("\"urteil\":\"GEDECKT\"", "\"urteil\":\"VIELLEICHT\"") + "\n", okButNoVerdict}) {
            Files.write(file.toPath(), (good + bad).getBytes(StandardCharsets.UTF_8));
            try {
                new SentenceVerdicts(project).open();
                fail("read: " + bad);
            } catch (CorruptFileException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("line 2"));
            }
        }
    }

    @Test
    public void aNewerVersionOrAnotherGenerationIsNeitherReadNorChanged() throws Exception {
        File project = scratch.newFolder("projekt");
        SentenceVerdicts verdicts = new SentenceVerdicts(project);
        verdicts.open();
        verdicts.record(covered(SENTENCE));
        File file = verdicts.logFile();
        String newer = "{\"v\":2,\"art\":\"urteil\"}\n";
        Files.write(file.toPath(), newer.getBytes(StandardCharsets.UTF_8));
        try {
            new SentenceVerdicts(project).open();
            fail("a newer version was read");
        } catch (SentenceVerdicts.NewerVersionException expected) {
            assertEquals(newer, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        Files.write(new File(file.getParentFile(), "current").toPath(),
                "verdicts-2.jsonl".getBytes(StandardCharsets.UTF_8));
        try {
            new SentenceVerdicts(project).open();
            fail("another generation was read");
        } catch (SentenceVerdicts.NewerVersionException expected) {
            assertEquals(file.getParentFile(), expected.getFile().getParentFile());
        }
    }

    @Test
    public void verdictsAreWrittenOnlyAfterOpeningAndInRamNothingGoesToDisk() throws Exception {
        SentenceVerdicts verdicts = new SentenceVerdicts(scratch.newFolder("projekt"));
        try {
            verdicts.record(covered(SENTENCE));
            fail("written before opening");
        } catch (IllegalStateException expected) {
            assertFalse(verdicts.logFile().exists());
        }
        SentenceVerdicts ram = new SentenceVerdicts();
        ram.record(covered(SENTENCE));
        assertEquals(1, ram.size());
        assertNull(ram.logFile());
    }
}
