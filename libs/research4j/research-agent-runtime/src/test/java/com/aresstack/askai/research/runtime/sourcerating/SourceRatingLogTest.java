package com.aresstack.askai.research.runtime.sourcerating;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Component 16: the append-only log of source ratings. */
public class SourceRatingLogTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static SourceRating ok(String id, String host, SourceRating.Kind kind) {
        return new SourceRating(id, "https://" + host + "/a", host, "Titel " + id, kind,
                SourceRating.Jurisdiction.DE, "2024-03", SourceRating.SelfInterest.NEIN, "Anbieter „A“",
                "Grund mit \"Zeichen\".", new KnownDomains.Entry(host, kind, SourceRating.Jurisdiction.DE, "Hinweis"),
                "gemma4:e2b", "2026-11-21T22:00:00Z", SourceRating.Status.OK, "", id, Arrays.asList("{\"a\":1}"));
    }

    private static SourceRating failed(String id, String host) {
        return new SourceRating(id, "https://" + host + "/a", host, "", null, null, "", null, "", "", null,
                "gemma4:e2b", "2026-11-21T22:01:00Z", SourceRating.Status.FEHLER, "model call failed (TIMEOUT): slow",
                id, Arrays.asList("", ""));
    }

    @Test
    public void ratingsComeBackAsWritten() throws IOException {
        SourceRatingLog log = new SourceRatingLog(folder.getRoot().toPath());
        log.append(Arrays.asList(ok("source-1", "dguv.de", SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG),
                failed("source-2", "firma.com")));

        Path file = folder.getRoot().toPath().resolve("source-ratings").resolve("log.jsonl");
        assertEquals(file, log.getFile());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("{\"v\":1,\"art\":\"quelle\",\"quelleId\":\"source-1\""));
        List<SourceRating> read = log.readAll();
        SourceRating first = read.get(0);
        assertEquals("dguv.de", first.getHost());
        assertEquals(SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG, first.getKind());
        assertEquals("Anbieter „A“", first.getPublisher());
        assertEquals("Grund mit \"Zeichen\".", first.getReason());
        assertEquals("Hinweis", first.getSuggestion().getNote());
        assertEquals(Arrays.asList("{\"a\":1}"), first.getAnswers());
        assertEquals("2024-03", first.getDate());
        SourceRating second = read.get(1);
        assertFalse(second.isOk());
        assertNull(second.getKind());
        assertNull(second.getSuggestion());
        assertEquals("model call failed (TIMEOUT): slow", second.getProblem());
        assertEquals(Arrays.asList("", ""), second.getAnswers());
    }

    @Test
    public void theLatestLineWinsAndAFailedHostIsAskedAgain() throws IOException {
        SourceRatingLog log = new SourceRatingLog(folder.getRoot().toPath());
        log.append(Arrays.asList(failed("source-1", "dguv.de")));
        log.append(Arrays.asList(ok("source-1", "dguv.de", SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG),
                ok("source-2", "firma.com", SourceRating.Kind.HERSTELLER_HANDEL)));
        log.append(Arrays.asList(failed("source-2", "firma.com")));

        Map<String, SourceRating> bySource = log.latestBySource();
        assertTrue(bySource.get("source-1").isOk());
        assertFalse(bySource.get("source-2").isOk());
        Map<String, SourceRating> byHost = log.latestOkByHost();
        assertTrue(byHost.containsKey("dguv.de"));
        assertFalse("its latest rating failed", byHost.containsKey("firma.com"));
        assertEquals(4, log.readAll().size());
    }

    @Test
    public void anUnfinishedLastLineIsSkippedAndCutBeforeTheNextAppend() throws IOException {
        SourceRatingLog log = new SourceRatingLog(folder.getRoot().toPath());
        log.append(Arrays.asList(ok("source-1", "dguv.de", SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG)));
        Files.write(log.getFile(), "{\"v\":1,\"art\":\"quelle\",\"quelleId\":\"sou".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        assertEquals(1, log.readAll().size());
        log.append(Arrays.asList(ok("source-2", "firma.com", SourceRating.Kind.HERSTELLER_HANDEL)));

        List<SourceRating> read = log.readAll();
        assertEquals(2, read.size());
        assertEquals("source-2", read.get(1).getSourceId());
    }

    @Test
    public void aBrokenLineInTheMiddleStopsTheRead() throws IOException {
        SourceRatingLog log = new SourceRatingLog(folder.getRoot().toPath());
        log.append(Arrays.asList(ok("source-1", "dguv.de", SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG)));
        Files.write(log.getFile(), "kein json\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        log.append(Arrays.asList(ok("source-2", "firma.com", SourceRating.Kind.HERSTELLER_HANDEL)));

        try {
            log.readAll();
            fail("fails closed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2 is no valid source rating"));
        }
    }

    @Test
    public void aNewerFormatStopsTheRead() throws IOException {
        SourceRatingLog log = new SourceRatingLog(folder.getRoot().toPath());
        Files.createDirectories(log.getFile().getParent());
        Files.write(log.getFile(), "{\"v\":2,\"art\":\"quelle\",\"quelleId\":\"source-1\"}\n"
                .getBytes(StandardCharsets.UTF_8));

        try {
            log.readAll();
            fail("an older app must not read on");
        } catch (SourceRatingLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 1: format 2 is newer than 1"));
        }
    }

    /** Review of PR #266: an older app leaves a log with a newer line untouched, finished or not. */
    @Test
    public void aNewerFormatStopsTheAppendBeforeAnythingIsWritten() throws IOException {
        SourceRatingLog log = new SourceRatingLog(folder.getRoot().toPath());
        Files.createDirectories(log.getFile().getParent());
        for (String newer : new String[]{"{\"v\":2,\"art\":\"quelle\",\"quelleId\":\"source-1\"}",
                "{\"v\":2,\"art\":\"quelle\",\"quelleId\":\"source-1\"}\n"}) {
            Files.write(log.getFile(), newer.getBytes(StandardCharsets.UTF_8));
            try {
                log.append(Arrays.asList(ok("source-2", "firma.com", SourceRating.Kind.HERSTELLER_HANDEL)));
                fail("an older app must not write on");
            } catch (SourceRatingLog.NewerVersionException expected) {
                assertEquals(newer, new String(Files.readAllBytes(log.getFile()), StandardCharsets.UTF_8));
            }
        }
    }

    /** Review of PR #266: reusing a host's rating keeps the date the model found for the page it read. */
    @Test
    public void theRatingOfThePageTheModelReadIsKeptOverItsCopies() throws IOException {
        SourceRatingLog log = new SourceRatingLog(folder.getRoot().toPath());
        SourceRating read = ok("source-1", "dguv.de", SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG);
        SourceSample other = new SourceSample("source-2", "https://dguv.de/b", "Titel 2", "dguv.de", "");
        log.append(Arrays.asList(read, read.forSource(other, "")));

        SourceRating kept = log.latestOkByHost().get("dguv.de");
        assertEquals("source-1", kept.getSourceId());
        assertEquals("2024-03", kept.getDate());
    }

    @Test
    public void noLogYetIsEmpty() throws IOException {
        SourceRatingLog log = new SourceRatingLog(folder.getRoot().toPath());
        assertTrue(log.readAll().isEmpty());
        assertTrue(log.latestOkByHost().isEmpty());
        log.append(Arrays.<SourceRating>asList());
        assertFalse(Files.exists(log.getFile()));
    }
}
