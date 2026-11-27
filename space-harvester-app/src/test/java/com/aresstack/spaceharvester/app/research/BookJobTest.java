package com.aresstack.spaceharvester.app.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BookJobTest {

    @TempDir
    Path output;

    @Test
    void anExistingBookIsNeverOverwritten() throws Exception {
        Path first = BookJob.save(output, "Korb", ".md", "eins");
        Path second = BookJob.save(output, "Korb", ".md", "zwei");

        assertThat(first.getFileName()).hasToString("Korb.md");
        assertThat(second.getFileName()).hasToString("Korb (2).md");
        assertThat(Files.readString(first, StandardCharsets.UTF_8)).isEqualTo("eins");
        assertThat(Files.readString(second, StandardCharsets.UTF_8)).isEqualTo("zwei");
    }

    @Test
    void twoRunsSavingAtOnceGetTwoFiles() throws Exception {
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        try {
            List<Callable<Path>> saves = IntStream.range(0, runs)
                    .<Callable<Path>>mapToObj(run -> () -> BookJob.save(output, "Korb", ".md", "Lauf " + run))
                    .toList();
            List<Path> saved = pool.invokeAll(saves).stream().map(BookJobTest::get).toList();

            assertThat(saved).doesNotHaveDuplicates().hasSize(runs);
            assertThat(saved.stream().map(path -> read(path))).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Path get(Future<Path> future) {
        try {
            return future.get();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }
}
