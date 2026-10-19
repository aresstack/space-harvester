package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link FileResolutionCache}: übersteht einen Neustart, verwirft kaputte oder fremde Dateien, je Quelle ersetzt. */
class FileResolutionCacheTest {

    @TempDir
    Path directory;

    @Test
    void survivesARestartAndReplacesPerSource() {
        FileResolutionCache cache = new FileResolutionCache(directory);
        cache.replace("wiki", Map.of("f1", List.of("r1", "r2"), "f2", List.of()));
        cache.replace("files", Map.of("f3", List.of("r3")));
        cache.replace("wiki", Map.of("f2", List.of()));

        FileResolutionCache reopened = new FileResolutionCache(directory);
        assertThat(reopened.find("f1")).isEmpty();
        assertThat(reopened.find("f2")).contains(List.of());
        assertThat(reopened.find("f3")).contains(List.of("r3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{kaputt",
        "[]",
        "{\"schema\":\"term-relations-ai-cache/v0\",\"sources\":{\"wiki\":{\"f1\":[\"r1\"]}}}",
        "{\"schema\":\"term-relations-ai-cache/v1\",\"sources\":{\"wiki\":{\"f1\":[{\"x\":1}]}}}"
    })
    void aDamagedOrForeignFileIsDiscardedAndRebuilt(String content) throws Exception {
        Path file = directory.resolve(FileResolutionCache.FILE);
        Files.writeString(file, content, StandardCharsets.UTF_8);

        FileResolutionCache cache = new FileResolutionCache(directory);
        assertThat(cache.find("f1")).isEmpty();

        cache.replace("wiki", Map.of("f2", List.of("r2")));
        assertThat(new FileResolutionCache(directory).find("f2")).contains(List.of("r2"));
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains(FileResolutionCache.SCHEMA);
    }

    @Test
    void anUnreadablePathDoesNotStopTheStart() throws Exception {
        Files.createDirectories(directory.resolve(FileResolutionCache.FILE));

        assertThat(new FileResolutionCache(directory).find("f1")).isEmpty();
    }
}
