package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.jobs.JobArea;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** ▶ an „Buch schreiben“ findet den Ordner des Buchs wieder (Angelo 27.11.2026). */
class BookFlowResumeTest {

    @TempDir
    Path dir;

    private static JobStatus book(Map<String, String> parameters) {
        return new JobStatus("j", JobType.BOOK, "*", null, "app", JobState.FAILED, new JobProgress(83, 3, 3, 0),
                new com.aresstack.spaceharvester.application.jobs.JobError("INTERRUPTED", "Neustart"), false,
                Instant.parse("2026-11-27T11:46:00Z"), null, null, null, null, parameters, false, false, null);
    }

    @Test
    void theFolderComesFromTheJobOrForOlderJobsFromItsUniqueName() {
        Favorites favorites = new Favorites(() -> dir.resolve("favoriten.json"));
        String recht = favorites.createFolder("Erfundenes Recht", null);
        String unter = favorites.createFolder("Unterordner", recht);

        assertEquals(unter, BookFlow.folderOf(book(Map.of(Workspace.BOOK_FOLDER, unter)), favorites, id -> null));
        assertEquals(unter, BookFlow.folderOf(book(JobArea.with(Map.of(), "Ordner „Unterordner“")), favorites,
                id -> null));
        assertNull(BookFlow.folderOf(book(JobArea.with(Map.of(), "Ordner „Gibt es nicht“")), favorites, id -> null));
        assertNull(BookFlow.folderOf(book(Map.of()), favorites, id -> null));
    }

    private static final Instant START = Instant.parse("2026-11-27T11:46:00Z");

    /**
     * Angelos Auftrag von 12:46 Uhr: weder Ordner-ID noch Bereich (der Titel eines Auftrags ohne Startseite wird nicht
     * abgelegt). Es gilt der Ordner, dessen Buch-Angaben unmittelbar vor dem Start gemerkt wurden.
     */
    @Test
    void aJobFromBeforeTheFolderParameterIsFoundByTheNotesMadeRightBeforeItsStart() {
        Favorites favorites = new Favorites(() -> dir.resolve("favoriten.json"));
        String ohne = favorites.createFolder("Ohne Buch", null);
        String korb = favorites.createFolder("Erfundener Korb", null);
        String frueher = favorites.createFolder("Früheres Buch", null);
        String spaeter = favorites.createFolder("Späteres Buch", null);
        Map<String, Instant> noted = Map.of(korb, START.minusSeconds(5), frueher, START.minusSeconds(7200),
                spaeter, START.plusSeconds(3600));

        assertEquals(korb, BookFlow.folderOf(book(Map.of()), favorites, noted::get));
        assertEquals(frueher, BookFlow.folderOf(book(Map.of()), favorites,
                Map.of(frueher, START.minusSeconds(7200), spaeter, START.plusSeconds(3600))::get));
        assertNull(BookFlow.folderOf(book(Map.of()), favorites, id -> null));
        assertNull(noted.get(ohne));
    }

    @Test
    void twoFoldersWithTheSameNameInTheAreaAreNotGuessed() {
        Favorites favorites = new Favorites(() -> dir.resolve("favoriten.json"));
        favorites.createFolder("Doppelt", null);
        String other = favorites.createFolder("Andere", null);
        favorites.createFolder("Doppelt", other);

        assertNull(BookFlow.folderOf(book(JobArea.with(Map.of(), "Ordner „Doppelt“")), favorites, id -> START));
    }
}
