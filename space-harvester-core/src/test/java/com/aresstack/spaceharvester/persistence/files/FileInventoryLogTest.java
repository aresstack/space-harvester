package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileInventoryLogTest {

    @TempDir
    Path directory;

    @Test
    void marksSurviveARestart() {
        Instant at = Instant.parse("2026-10-19T12:00:00Z");
        new FileInventoryLog(directory).mark("wiki", "DEV/Ä b", at);

        FileInventoryLog reopened = new FileInventoryLog(directory);
        assertThat(reopened.since("wiki", "DEV/Ä b")).contains(at);
        assertThat(reopened.since("wiki", "OPS")).isEmpty();
    }

    @Test
    void anUnreadableFileStartsEmpty() throws IOException {
        Files.writeString(directory.resolve(FileInventoryLog.FILE), "wiki/DEV=kaputt", StandardCharsets.UTF_8);

        assertThat(new FileInventoryLog(directory).since("wiki", "DEV")).isEmpty();
    }
}
