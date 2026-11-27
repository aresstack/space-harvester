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
        return new JobStatus("j", JobType.BOOK, "*", null, "app", JobState.CANCELLED, new JobProgress(83, 3, 3, 0),
                null, false, Instant.parse("2026-11-27T11:46:00Z"), null, null, null, null, parameters, false, false,
                null);
    }

    @Test
    void theFolderComesFromTheJobOrForOlderJobsFromItsUniqueName() {
        Favorites favorites = new Favorites(() -> dir.resolve("favoriten.json"));
        String recht = favorites.createFolder("Erfundenes Recht", null);
        String unter = favorites.createFolder("Unterordner", recht);

        assertEquals(unter, BookFlow.folderOf(book(Map.of(Workspace.BOOK_FOLDER, unter)), favorites));
        assertEquals(unter, BookFlow.folderOf(book(JobArea.with(Map.of(), "Ordner „Unterordner“")), favorites));
        assertNull(BookFlow.folderOf(book(JobArea.with(Map.of(), "Ordner „Gibt es nicht“")), favorites));
        assertNull(BookFlow.folderOf(book(Map.of()), favorites));
    }

    @Test
    void twoFoldersWithTheSameNameAreNotGuessed() {
        Favorites favorites = new Favorites(() -> dir.resolve("favoriten.json"));
        favorites.createFolder("Doppelt", null);
        String other = favorites.createFolder("Andere", null);
        favorites.createFolder("Doppelt", other);

        assertNull(BookFlow.folderOf(book(JobArea.with(Map.of(), "Ordner „Doppelt“")), favorites));
    }
}
