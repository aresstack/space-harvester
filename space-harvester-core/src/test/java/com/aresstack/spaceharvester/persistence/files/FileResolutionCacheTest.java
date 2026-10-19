package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FileResolutionCache}: übersteht einen Neustart, beginnt bei Schaden leer, je Quelle ersetzt. */
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

    @Test
    void aDamagedFileStartsEmpty() throws Exception {
        Files.writeString(directory.resolve(FileResolutionCache.FILE), "{kaputt", StandardCharsets.UTF_8);

        assertThat(new FileResolutionCache(directory).find("f1")).isEmpty();
    }
}
