package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gemeinsame Ablage der Markierungen (Indexieren, Ausschließen): atomar, übersteht Neustarts, unlesbar = leer. */
class SpaceMarksFileTest {

    @TempDir
    Path dir;

    @Test
    void marksSurviveARestartAndTheWholeSpaceIsStoredAsGiven() {
        Path file = dir.resolve("marks.properties");
        SpaceMarksFile marks = new SpaceMarksFile(file, "", "Test");
        marks.add("wiki", "DEV", "100");
        marks.add("wiki", "DEV", null);
        marks.add("wiki", "HR", "7");

        SpaceMarksFile reopened = new SpaceMarksFile(file, "", "Test");

        assertThat(reopened.ids("wiki", "DEV")).containsExactlyInAnyOrder("100", "");
        assertThat(reopened.spaces("wiki")).containsExactly("DEV", "HR");
        assertThat(reopened.spaces("other")).isEmpty();

        reopened.remove("wiki", "HR", "7");
        assertThat(new SpaceMarksFile(file, "", "Test").spaces("wiki")).containsExactly("DEV");
    }

    @Test
    void invalidIdsAreRejectedAndAnUnreadableFileStartsEmpty() throws Exception {
        Path file = dir.resolve("marks.properties");
        SpaceMarksFile marks = new SpaceMarksFile(file, "", "Test");
        assertThatThrownBy(() -> marks.add("wiki", "DEV", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> marks.add("wiki", "DEV", "a\nb")).isInstanceOf(IllegalArgumentException.class);

        Files.writeString(file, "wiki/DEV=\\u00", StandardCharsets.ISO_8859_1);
        assertThat(new SpaceMarksFile(file, "", "Test").ids("wiki", "DEV")).isEmpty();
    }

    @Test
    void indexAndExclusionMarksKeepSeparateFiles() {
        new FileIndexMarks(dir).mark("wiki", "DEV", "100");
        new FileExclusionMarks(dir).mark("wiki", "DEV", "200");

        assertThat(new FileIndexMarks(dir).roots("wiki", "DEV")).containsExactly("100");
        assertThat(new FileExclusionMarks(dir).roots("wiki", "DEV")).containsExactly("200");
        assertThat(dir.resolve(FileIndexMarks.FILE)).exists();
        assertThat(dir.resolve(FileExclusionMarks.FILE)).exists();
    }
}
