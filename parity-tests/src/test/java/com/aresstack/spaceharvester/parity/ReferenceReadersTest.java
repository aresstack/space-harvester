package com.aresstack.spaceharvester.parity;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ParityConfluence;
import com.aresstack.spaceharvester.parity.DocumentOutline.Table;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.aresstack.spaceharvester.parity.CoreParityTest.reference;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Leser erkennen, was der Enterprise AI Client nachweislich schreibt (Referenzexporte des Paritäts-Space): sonst
 * wäre jeder Vergleich wertlos.
 */
class ReferenceReadersTest {

    @Test
    void wordFromTheEnterpriseClientHasTocFieldImagesTableAndAllChapters() throws IOException {
        DocumentOutline docx = DocxReader.read(reference("parity.docx"));

        assertThat(docx.tableOfContents()).isTrue();
        assertThat(docx.updateFieldsOnOpen()).isTrue();
        // Systembild, gerendertes Mermaid und Bilder der Seite Anhänge; fehlt.png und das externe Bild nicht
        assertThat(docx.images()).isEqualTo(5);
        assertThat(docx.imageNotes()).isEqualTo(2);
        assertThat(docx.tables()).containsExactly(new Table(3, 3, true));
        assertThat(docx.headings()).extracting(DocumentOutline.Heading::text)
                .contains("Paritätsprüfung", "Handbuch", "Zweck", "Architektur", "Komponenten", "Ebene 7", "Betrieb");
        assertThat(docx.headings()).extracting(DocumentOutline.Heading::level).contains(9);
        assertThat(docx.text()).contains(ParityConfluence.SENTENCES).doesNotContain("F9");
    }

    @Test
    void pdfFromTheEnterpriseClientHasTocPageNumbersOutlineAndImages() throws IOException {
        DocumentOutline pdf = PdfReader.read(reference("parity.pdf"));

        assertThat(pdf.tableOfContents()).isTrue();
        assertThat(pdf.pageNumbers()).isTrue();
        assertThat(pdf.outlineEntries()).isGreaterThanOrEqualTo(9);
        assertThat(pdf.images()).isEqualTo(4);
        assertThat(pdf.internalLinks()).isGreaterThanOrEqualTo(9);
        assertThat(pdf.tables()).isNull();
        assertThat(pdf.text().replaceAll("\\s+", " ")).contains(ParityConfluence.SENTENCES);
    }

    @Test
    void markdownFromTheEnterpriseClientEmbedsImagesAndKeepsMermaid() throws IOException {
        DocumentOutline md = MarkdownReader.read(reference("parity.md"));

        assertThat(md.images()).isEqualTo(5);
        assertThat(md.imageNotes()).isEqualTo(2);
        assertThat(md.mermaidSources()).isEqualTo(1);
        assertThat(md.tables()).containsExactly(new Table(3, 3, true));
        // Kapitel unterhalb von Ebene 6 schreibt der Enterprise AI Client fett
        assertThat(md.headings()).extracting(DocumentOutline.Heading::level).contains(7);
        assertThat(md.tableOfContents()).isFalse();
        assertThat(md.text()).contains(ParityConfluence.SENTENCES);
    }

    @Test
    void markdownLinksAndLocalImagesAreCounted() {
        DocumentOutline md = MarkdownReader.parse("""
                - [Eins](#eins)
                - [Zwei](#zwei)

                # Eins

                Siehe [Doku](https://example.org/doku) und <mailto:team@example.org>.
                ![Bild](attachment:bild.png)

                | A | B |
                |---|---|
                | 1 | 2 |
                """, null);

        assertThat(md.tableOfContents()).isTrue();
        assertThat(md.internalLinks()).isEqualTo(2);
        assertThat(md.externalLinks()).containsExactlyInAnyOrder("https://example.org/doku", "mailto:team@example.org");
        assertThat(md.images()).isZero();
        assertThat(md.imageNotes()).isEqualTo(1);
        assertThat(md.tables()).containsExactly(new Table(2, 2, true));
    }
}
