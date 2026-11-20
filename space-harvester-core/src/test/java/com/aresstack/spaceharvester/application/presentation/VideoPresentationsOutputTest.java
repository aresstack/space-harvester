package com.aresstack.spaceharvester.application.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Angelo 20.11.2026: Die Datei landet lesbar benannt im eingestellten Ausgabeordner, nie in einem Hash-Ordner. */
class VideoPresentationsOutputTest {

    @Test
    void aTakenNameGetsANumberLikeABrowserDownload(@TempDir Path downloads) throws Exception {
        assertThat(VideoPresentations.freeName(downloads, "Vortrag.pptx")).isEqualTo(downloads.resolve("Vortrag.pptx"));

        Files.writeString(downloads.resolve("Vortrag.pptx"), "alt");
        Files.writeString(downloads.resolve("Vortrag (2).pptx"), "alt");

        assertThat(VideoPresentations.freeName(downloads, "Vortrag.pptx"))
                .isEqualTo(downloads.resolve("Vortrag (3).pptx"));
    }

    @Test
    void optionsCarryTheDirectoryThroughFormatChanges() {
        Path downloads = Path.of("Downloads").toAbsolutePath();

        VideoPresentations.Options options = VideoPresentations.Options.DEFAULT.withDirectory(downloads)
                .withFormat(VideoPresentations.Format.PDF);

        assertThat(options.directory()).isEqualTo(downloads);
        assertThat(options.format()).isEqualTo(VideoPresentations.Format.PDF);
        assertThat(VideoPresentations.Options.DEFAULT.directory()).isNull();
    }
}
