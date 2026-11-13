package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.TextSection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Lesetext eines Abschnitts (ADR 0026, AP9a): nur Text der Blöcke, je Block eine Zeile, Tabellen je Zeile. */
class DocumentSectionTextTest {

    private static final ExtractionKey KEY = new ExtractionKey("sha256:" + "a".repeat(64), "tika", "3.3.2", "1");

    @Test
    void blocksBecomeLinesHeadingsSectionsAndTableRowsTheirOwnLines() {
        TextSection section = new TextSection(0, 0, List.of(
                new Block.Heading(0, Optional.empty(), List.of(), 1, "Einleitung"),
                new Block.Paragraph(1, Optional.empty(), List.of(), "Erster Satz,\numbrochen."),
                new Block.Table(2, Optional.empty(), List.of(), List.of(
                        new Block.Cell(0, 0, "Name", true, Optional.empty()),
                        new Block.Cell(0, 1, "Wert", true, Optional.empty()),
                        new Block.Cell(1, 0, "A", false, Optional.empty()),
                        new Block.Cell(1, 1, "1\n2", false, Optional.empty())), false),
                new Block.Paragraph(3, Optional.empty(), List.of(), "  "),
                new Block.Heading(4, Optional.empty(), List.of(), 2, "Schluss"),
                new Block.ListItem(5, Optional.empty(), List.of(), false, 1, "Punkt")), 60);

        PageText text = DocumentSectionText.project(KEY, section);

        assertThat(text.text()).isEqualTo("Einleitung\nErster Satz, umbrochen.\nName | Wert\nA | 1 2\nSchluss\nPunkt");
        assertThat(text.sections()).containsExactly(new PageText.Section("Einleitung", 1, 0, 55),
                new PageText.Section("Schluss", 2, 55, text.text().length()));
        assertThat(text.schema()).isEqualTo("document-text/v1+tika:3.3.2:1");
    }

    @Test
    void anotherExtractionVersionChangesTheInput() {
        TextSection section = new TextSection(0, 0, List.of(new Block.Paragraph(0, Optional.empty(), List.of(),
                "Text")), 4);
        ExtractionKey newer = new ExtractionKey(KEY.sha256(), "tika", "3.4.0", "1");

        assertThat(DocumentSectionText.project(newer, section).inputSha256())
                .isNotEqualTo(DocumentSectionText.project(KEY, section).inputSha256());
    }
}
