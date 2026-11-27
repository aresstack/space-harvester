package com.aresstack.spaceharvester.app.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookEditionsTest {

    @TempDir
    Path data;

    @Test
    void eachRunAddsAnEditionAndNoneIsOverwritten() throws Exception {
        Instant first = Instant.parse("2026-11-27T10:00:00Z");
        List<String> heard = new ArrayList<>();
        BookEditions.Edition one;
        try (AutoCloseable listening = BookEditions.onAdded(heard::add)) {
            one = BookEditions.add(data, "ordner-1", first, "# Erste", "Bericht 1",
                    List.of(new BookEditions.Source("wiki", "7", "abc")));
            BookEditions.add(data, "ordner-1", first.plusSeconds(60), "# Zweite", "Bericht 2", List.of());
        }

        assertEquals(1, one.number());
        assertEquals(List.of("ordner-1", "ordner-1"), heard);
        BookEditions.Edition latest = BookEditions.latest(data, "ordner-1").orElseThrow();
        assertEquals(2, latest.number());
        assertEquals("# Zweite", latest.book());
        Path directory = BookEditions.directory(data, "ordner-1");
        assertTrue(Files.readString(directory.resolve("auflage-0001.json")).contains("# Erste"));
        assertTrue(Files.readString(directory.resolve("auflage-0001.json")).contains("\"version\" : \"abc\""));
    }

    @Test
    void aFolderWithoutBookHasNoEditionAndAnUnreadableNewestFallsBackToTheOneBefore() throws Exception {
        assertTrue(BookEditions.latest(data, "ordner-2").isEmpty());
        BookEditions.add(data, "ordner-2", Instant.parse("2026-11-27T10:00:00Z"), "# Lesbar", "", List.of());
        Files.writeString(BookEditions.directory(data, "ordner-2").resolve("auflage-0002.json"), "kaputt");

        assertEquals("# Lesbar", BookEditions.latest(data, "ordner-2").orElseThrow().book());
        assertEquals(3, BookEditions.add(data, "ordner-2", Instant.now(), "# Neu", "", List.of()).number());
    }

    @Test
    void fileNamesUseAsciiDigitsWhateverTheLocale() throws Exception {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"));
            BookEditions.add(data, "ordner-4", Instant.parse("2026-11-27T10:00:00Z"), "# Buch", "", List.of());
        } finally {
            java.util.Locale.setDefault(before);
        }

        assertTrue(Files.isRegularFile(BookEditions.directory(data, "ordner-4").resolve("auflage-0001.json")));
        assertEquals("# Buch", BookEditions.latest(data, "ordner-4").orElseThrow().book());
    }

    @Test
    void anEditionIsStaleOnlyWhenAReadPageHasANewerKnownVersion() throws Exception {
        assertTrue(!BookEditions.exists(data, "ordner-3"));
        BookEditions.Edition edition = BookEditions.add(data, "ordner-3", Instant.parse("2026-11-27T10:00:00Z"),
                "# Buch", "", List.of(new BookEditions.Source("wiki", "1", "v1"),
                        new BookEditions.Source("wiki", "2", null)));
        assertTrue(BookEditions.exists(data, "ordner-3"));

        assertTrue(!BookEditions.stale(edition, null, source -> "1".equals(source.pageId()) ? "v1" : "v9"));
        assertTrue(!BookEditions.stale(edition, null, source -> null));
        assertTrue(BookEditions.stale(edition, null, source -> "1".equals(source.pageId()) ? "v2" : null));
        assertTrue(!BookEditions.stale(null, null, source -> "v2"));
    }

    @Test
    void anAddedOrRemovedPageOfTheFolderMakesTheEditionStale() throws Exception {
        BookEditions.Source one = new BookEditions.Source("wiki", "1", null);
        BookEditions.Source two = new BookEditions.Source("wiki", "2", null);
        BookEditions.Edition edition = BookEditions.add(data, "ordner-4", Instant.parse("2026-11-27T10:00:00Z"),
                "# Buch", "", List.of(new BookEditions.Source("wiki", "1", "v1")), List.of(one));

        BookEditions.Edition read = BookEditions.latest(data, "ordner-4").orElseThrow();
        assertEquals(List.of(one), read.members());
        assertTrue(!BookEditions.stale(read, List.of(new BookEditions.Source("wiki", "1", "egal")), source -> "v1"));
        assertTrue(BookEditions.stale(read, List.of(one, two), source -> "v1"));
        assertTrue(BookEditions.stale(read, List.of(), source -> "v1"));
        assertTrue(!BookEditions.stale(new BookEditions.Edition(1, edition.createdAt(), "", "", edition.sources()),
                List.of(), source -> "v1"));
    }
}
