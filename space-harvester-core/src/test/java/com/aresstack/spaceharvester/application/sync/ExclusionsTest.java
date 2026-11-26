package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.persistence.files.FileExclusionMarks;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ausschließen (Angelo 26.11.2026): eine Stelle entscheidet, Teilbäume folgen ihren Wurzeln, gelöscht wird nichts. */
class ExclusionsTest {

    /** Bestand: Bibliothek L mit Ordner A, darin Datei a1; Ordner B daneben. */
    private static final Map<String, String> PARENTS = Map.of("A", "L", "a1", "A", "B", "L");
    private static final Map<String, String> TITLES = Map.of("A", "Style Library", "B", "Freigegebene Dokumente");

    @TempDir
    Path dir;

    private Exclusions exclusions() {
        return new Exclusions(new FileExclusionMarks(dir), (sourceId, pageId) -> PARENTS.get(pageId),
                (sourceId, pageId) -> TITLES.get(pageId));
    }

    @Test
    void aFolderExcludesEverythingBelowItButNothingBesideIt() {
        Exclusions exclusions = exclusions();
        assertThat(exclusions.any("sp")).isFalse();

        exclusions.exclude("sp", "sp", "A");

        assertThat(exclusions.any("sp")).isTrue();
        assertThat(exclusions.excluded("sp", "sp", "A")).isTrue();
        assertThat(exclusions.excluded("sp", "sp", "a1")).isTrue();
        assertThat(exclusions.excluded("sp", "sp", "B")).isFalse();
        assertThat(exclusions.excluded("sp", "sp", "L")).isFalse();
        // Ohne bekannten Bereich zählen die Seitenwurzeln aller Bereiche.
        assertThat(exclusions.excluded("sp", null, "a1")).isTrue();
        // Andere Quellen bleiben unberührt.
        assertThat(exclusions.excluded("other", "sp", "a1")).isFalse();
    }

    @Test
    void theInventoryOfARunNamesAncestorsTheStoreDoesNotKnowYet() {
        Exclusions exclusions = exclusions();
        exclusions.exclude("sp", "sp", "B");

        Predicate<String> run = exclusions.within("sp", "sp", Map.of("b9", "b8", "b8", "B")::get);

        assertThat(run.test("b9")).isTrue();
        assertThat(run.test("a1")).isFalse();
    }

    @Test
    void aWholeSpaceExcludesEveryPageAndTheSpaceItself() {
        Exclusions exclusions = exclusions();
        exclusions.exclude("wiki", "HR", null);

        assertThat(exclusions.spaceExcluded("wiki", "HR")).isTrue();
        assertThat(exclusions.excluded("wiki", "HR", "anything")).isTrue();
        assertThat(exclusions.within("wiki", "HR", null).test(null)).isTrue();
        assertThat(exclusions.excluded("wiki", "DEV", "anything")).isFalse();
    }

    @Test
    void includingRestoresExactlyThatExclusionAndTheOverviewNamesWhatIsLeft() {
        Exclusions exclusions = exclusions();
        exclusions.exclude("sp", "sp", "A");
        exclusions.exclude("sp", "sp", "B");
        exclusions.exclude("sp", "other", null);
        exclusions.exclude("sp", "sp", "unbekannt");

        assertThat(exclusions.of("sp")).extracting(Exclusions.Exclusion::spaceKey, Exclusions.Exclusion::pageId,
                Exclusions.Exclusion::title).containsExactly(
                org.assertj.core.groups.Tuple.tuple("other", null, ""),
                org.assertj.core.groups.Tuple.tuple("sp", "unbekannt", ""),
                org.assertj.core.groups.Tuple.tuple("sp", "B", "Freigegebene Dokumente"),
                org.assertj.core.groups.Tuple.tuple("sp", "A", "Style Library"));

        exclusions.include("sp", "sp", "A");

        assertThat(exclusions.excluded("sp", "sp", "a1")).isFalse();
        assertThat(exclusions.excluded("sp", "sp", "B")).isTrue();
        // Die Ablage übersteht einen Neustart.
        assertThat(exclusions().of("sp")).hasSize(3);
    }
}
