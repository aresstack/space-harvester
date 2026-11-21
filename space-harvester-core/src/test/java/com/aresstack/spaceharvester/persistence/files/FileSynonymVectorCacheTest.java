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
    void unusedVectorsOfASourceFallAfterACompleteRun() throws Exception {
        PageKey one = new PageKey("wiki", "1");
        PageKey foreign = new PageKey("other", "1");
        FileSynonymVectorCache cache = new FileSynonymVectorCache(directory);
        cache.putAll("model@1", Map.of("old", new SynonymVectorCache.Entry(new float[] {1f}, Set.of(one)),
                "new", new SynonymVectorCache.Entry(new float[] {1f}, Set.of(one)),
                "mixed", new SynonymVectorCache.Entry(new float[] {1f}, Set.of(one, foreign))));
        cache.putAll("model@0", Map.of("new", new SynonymVectorCache.Entry(new float[] {1f}, Set.of(one))));

        cache.retainSource("wiki", "model@1", Set.of("new"));

        FileSynonymVectorCache reopened = new FileSynonymVectorCache(directory);
        assertThat(reopened.find("model@1", "new")).isPresent();
        assertThat(reopened.find("model@1", "old")).isEmpty();
        assertThat(reopened.find("model@0", "new")).isEmpty();
        assertThat(reopened.find("model@1", "mixed")).as("auch aus einer anderen Quelle").isPresent();
    }

    /** Messung für den Architekten: 2.000 Vektoren mit 1.024 Dimensionen, je zwei Seiten. */
    @Test
    void twoThousandVectorsGiveAFileOfMeasuredSize() throws Exception {
        FileSynonymVectorCache cache = new FileSynonymVectorCache(directory);
        Map<String, SynonymVectorCache.Entry> entries = new java.util.HashMap<>();
        for (int i = 0; i < 2_000; i++) {
            float[] vector = new float[1024];
            vector[i % 1024] = 1f;
            entries.put(String.format("%064x", i), new SynonymVectorCache.Entry(vector,
                    Set.of(new PageKey("wiki", "p" + i), new PageKey("wiki", "q" + i))));
        }
        cache.putAll("model@1", entries);
        long bytes = Files.size(directory.resolve(FileSynonymVectorCache.FILE));
        System.out.println("SY2-Messung: 2000 Vektoren x 1024 Dimensionen = " + bytes + " Bytes");
        assertThat(bytes).isPositive();
    }

    @Test
    void anUnreadableFileStartsEmpty() throws Exception {
        Files.writeString(directory.resolve(FileSynonymVectorCache.FILE), "{kaputt");

        assertThat(new FileSynonymVectorCache(directory).state("wiki")).isEmpty();
    }
}
