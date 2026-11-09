package com.aresstack.spaceharvester.app.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * „Erweitert“ zeichnet seinen Pfeil selbst: Segoe UI Semibold (die halbfette Schrift unter Windows) hat ▸ und ▾
 * nicht und zeigte ein Kästchen. Hilfetexte nennen das Auswahlmenü in Worten statt mit dem Zeichen.
 */
class DisclosureGlyphTest {

    @Test
    void theArrowIsPaintedAndFollowsTheState() {
        JButton header = Comic.disclosure("Erweitert", "Mehr");
        assertThat(header.getText()).isEqualTo("Erweitert");
        assertThat(header.getIcon()).isNotNull();
        assertThat(Comic.disclosed(header)).isFalse();
        Comic.disclose(header, true);
        assertThat(Comic.disclosed(header)).isTrue();
    }

    @Test
    void noTextInTheUiUsesTheArrowGlyphsInSentences() throws IOException {
        Path ui = Path.of("src/main/java/com/aresstack/spaceharvester/app/ui");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(ui)) {
            offenders = files.filter(file -> file.toString().endsWith(".java")).flatMap(file -> {
                try {
                    return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                            .filter(line -> !line.strip().startsWith("*") && !line.strip().startsWith("//"))
                            .filter(line -> line.contains("über ▾") || line.contains("▸ Erweitert")
                                    || line.contains("▾ Erweitert"))
                            .map(line -> file.getFileName() + ": " + line.strip());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
        assertThat(offenders).isEmpty();
    }
}
