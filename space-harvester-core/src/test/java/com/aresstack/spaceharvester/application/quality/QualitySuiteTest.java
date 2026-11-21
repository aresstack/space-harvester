package com.aresstack.spaceharvester.application.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Testbestand lesen (Q1); nur erfundene Fragen. */
class QualitySuiteTest {

    private static final PageResolver RESOLVER = url -> url.contains("pageId=")
            ? Optional.of(new PageRef("foerder", url.substring(url.indexOf("pageId=") + 7)))
            : Optional.empty();

    @Test
    void theExampleInTheDocsIsReadCompletely() throws Exception {
        QualitySuite suite = QualitySuite.read(Files.readAllBytes(Path.of("../docs/suchqualitaet/beispiel-fragen.tsv")),
                RESOLVER);

        assertThat(suite.errors()).isEmpty();
        assertThat(suite.cases()).extracting(QualityCase::id).containsExactly("F01", "F02", "F03", "F04", "F05");
        QualityCase spread = suite.cases().get(1);
        assertThat(spread.scope()).containsExactly(ScopeSpec.space("foerder", "FV"));
        assertThat(spread.expected()).containsExactly(new PageRef("foerder", "1004"), new PageRef("foerder", "1007"));
        assertThat(spread.kinds()).containsExactly(CaseKind.VERTEILT);
        QualityCase synonym = suite.cases().get(3);
        assertThat(synonym.scope()).containsExactly(ScopeSpec.page(new PageRef("foerder", "1000"), true));
        assertThat(synonym.expected()).containsExactly(new PageRef("foerder", "1002"));
        assertThat(suite.cases().get(4).noAnswer()).isTrue();
        assertThat(suite.cases().get(4).scope()).containsExactly(ScopeSpec.all());
    }

    @Test
    void anInvalidLineIsAnErrorWithItsNumberAndTheOthersStay() {
        String text = String.join("\n",
                "# Kommentar",
                "id\tfrage\tbereich\terwartet\tart",
                "A1\tFrage eins\tq\tq:1\t",
                "A2\tFrage zwei\tq\thttps://unbekannt.example/x\t",
                "A3\tFrage drei\tq\t\teinfach",
                "A4\tFrage vier\tq\tq:4\tkeine",
                "A5\tFrage fünf\tq\tq:5\tseltsam",
                "A1\tdoppelt\tq\tq:1\t",
                "A7\tFrage sieben\tq:SP+unterseiten\tq:7\t",
                "A8\tFrage acht\t*\tq:seite:8\t");

        QualitySuite suite = QualitySuite.parse(text, RESOLVER);

        assertThat(suite.cases()).extracting(QualityCase::id).containsExactly("A1", "A8");
        assertThat(suite.cases().get(1).expected()).containsExactly(new PageRef("q", "8"));
        assertThat(suite.errors()).extracting(QualitySuite.LineError::line).containsExactly(4, 5, 6, 7, 8, 9);
        assertThat(suite.errors().get(0).message()).doesNotContain("unbekannt.example");
    }

    @Test
    void missingColumnsStopReadingWithOneError() {
        QualitySuite suite = QualitySuite.parse("id;frage;bereich;erwartet\nA1;x;q;q:1\n", RESOLVER);

        assertThat(suite.cases()).isEmpty();
        assertThat(suite.errors()).singleElement().satisfies(error -> {
            assertThat(error.line()).isEqualTo(1);
            assertThat(error.message()).contains("Spaltenköpfe fehlen");
        });
    }

    @Test
    void excelUnicodeTextIsUtf16WithBom() {
        String text = "id\tfrage\tbereich\terwartet\nÄ1\t\"Wer prüft Anträge?\"\tq\tq:1\n";
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] content = new byte[body.length + 2];
        content[0] = (byte) 0xff;
        content[1] = (byte) 0xfe;
        System.arraycopy(body, 0, content, 2, body.length);

        QualitySuite suite = QualitySuite.read(content, PageResolver.NONE);

        assertThat(suite.errors()).isEmpty();
        assertThat(suite.cases()).singleElement().satisfies(question -> {
            assertThat(question.id()).isEqualTo("Ä1");
            assertThat(question.question()).isEqualTo("Wer prüft Anträge?");
        });
    }
}
