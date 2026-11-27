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
}
