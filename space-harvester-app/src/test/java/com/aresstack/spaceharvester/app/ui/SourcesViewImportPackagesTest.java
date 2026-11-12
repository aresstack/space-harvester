package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** „JSON-Paket importieren“ nimmt mehrere Dateien und ganze Ordner auf einmal. */
class SourcesViewImportPackagesTest {

    @TempDir
    Path root;

    @Test
    void aFolderStandsForAllZipFilesInsideAndFilesAreTakenOnce() throws Exception {
        Path folder = Files.createDirectory(root.resolve("pakete"));
        Path ops = Files.writeString(folder.resolve("OPS.zip"), "x");
        Path dev = Files.writeString(folder.resolve("DEV.ZIP"), "x");
        Files.writeString(folder.resolve("liesmich.txt"), "x");
        Files.createDirectory(folder.resolve("alt.zip"));
        Path single = Files.writeString(root.resolve("QA.zip"), "x");

        assertThat(SourcesView.packageFiles(List.of(single, folder, ops))).containsExactly(single, dev, ops);
    }

    @Test
    void longListsOfSpacesAreShortened() {
        assertThat(SourcesView.shortList(List.of("A", "B"))).isEqualTo("A, B");
        assertThat(SourcesView.shortList(List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")))
                .isEqualTo("A, B, C, D, E, F, G, H und 2 weitere");
    }
}
