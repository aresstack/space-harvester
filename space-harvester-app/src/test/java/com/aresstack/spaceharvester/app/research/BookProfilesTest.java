package com.aresstack.spaceharvester.app.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookProfilesTest {

    @TempDir
    Path data;

    @Test
    void aRememberedProfileSurvivesARestartOfTheApp() {
        BookProfiles first = new BookProfiles(data, "ordner-1");
        first.remember("a1", "{\"title\":\"Erfundene Quelle\"}");
        first.remember("b2", "{\"title\":\"Zweite Quelle\"}");

        BookProfiles afterRestart = new BookProfiles(data, "ordner-1");

        assertEquals(2, afterRestart.size());
        assertEquals("{\"title\":\"Erfundene Quelle\"}", afterRestart.recall("a1"));
        assertNull(afterRestart.recall("unbekannt"));
    }

    @Test
    void eachFolderKeepsItsOwnProfilesNextToItsNotes() {
        new BookProfiles(data, "ordner-1").remember("a1", "{}");

        assertNull(new BookProfiles(data, "ordner-2").recall("a1"));
        Path notes = BookNotes.file(data, "ordner-1");
        String name = notes.getFileName().toString();
        assertTrue(Files.isRegularFile(notes.resolveSibling(name.replace(".json", ".profiles.json"))));
    }

    @Test
    void anUnreadableFileStartsEmptyInsteadOfFailing() throws Exception {
        Path notes = BookNotes.file(data, "ordner-1");
        Files.createDirectories(notes.getParent());
        String name = notes.getFileName().toString();
        Files.writeString(notes.resolveSibling(name.replace(".json", ".profiles.json")), "kein json");

        BookProfiles profiles = new BookProfiles(data, "ordner-1");

        assertEquals(0, profiles.size());
        profiles.remember("a1", "{}");
        assertEquals("{}", new BookProfiles(data, "ordner-1").recall("a1"));
    }
}
