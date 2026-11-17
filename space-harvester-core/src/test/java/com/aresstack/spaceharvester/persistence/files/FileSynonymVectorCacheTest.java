package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.SynonymVectorCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dauerhafter Vektor-Cache der Synonymerkennung (SY2): übersteht einen Neustart, fällt mit Seite und Quelle. */
class FileSynonymVectorCacheTest {

    @TempDir
    Path directory;

    @Test
    void vectorsAndStatesSurviveARestartAndFallWithTheirPages() throws Exception {
        PageKey one = new PageKey("wiki", "1");
        PageKey two = new PageKey("wiki", "2");
        FileSynonymVectorCache cache = new FileSynonymVectorCache(directory);
        cache.putAll("model@1", Map.of("a", new SynonymVectorCache.Entry(new float[] {0.5f, -1f}, Set.of(one)),
                "b", new SynonymVectorCache.Entry(new float[] {1f, 0f}, Set.of(two))));
        cache.state("wiki", "stand");

        FileSynonymVectorCache reopened = new FileSynonymVectorCache(directory);
        assertThat(reopened.find("model@1", "a")).hasValueSatisfying(vector ->
                assertThat(vector).containsExactly(0.5f, -1f));
        assertThat(reopened.find("model@2", "a")).isEmpty();
        assertThat(reopened.state("wiki")).contains("stand");

        reopened.withdraw(List.of(one));
        assertThat(new FileSynonymVectorCache(directory).find("model@1", "a")).isEmpty();
        assertThat(new FileSynonymVectorCache(directory).find("model@1", "b")).isPresent();

        reopened.withdrawSource("wiki");
        FileSynonymVectorCache purged = new FileSynonymVectorCache(directory);
        assertThat(purged.find("model@1", "b")).isEmpty();
        assertThat(purged.state("wiki")).isEmpty();
        assertThat(Files.readString(directory.resolve(FileSynonymVectorCache.FILE))).doesNotContain("Auto");
    }

    @Test
    void anUnreadableFileStartsEmpty() throws Exception {
        Files.writeString(directory.resolve(FileSynonymVectorCache.FILE), "{kaputt");

        assertThat(new FileSynonymVectorCache(directory).state("wiki")).isEmpty();
    }
}
