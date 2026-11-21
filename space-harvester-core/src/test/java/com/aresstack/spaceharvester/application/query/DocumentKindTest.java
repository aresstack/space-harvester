package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentKindTest {

    @Test
    void aKnownExtensionDecidesBeforeTheMediaType() {
        assertThat(DocumentKind.of("Regeltermin_2024-04-17.pptx", null)).isEqualTo(DocumentKind.PRESENTATION);
        assertThat(DocumentKind.of("Gesamtkontaktliste HaVdB.XLSX", "application/octet-stream"))
                .isEqualTo(DocumentKind.SPREADSHEET);
        assertThat(DocumentKind.of("bericht.docx", "application/pdf")).isEqualTo(DocumentKind.WORD);
        assertThat(DocumentKind.of("plan.pdf", null)).isEqualTo(DocumentKind.PDF);
        assertThat(DocumentKind.of("foto.jpeg", null)).isEqualTo(DocumentKind.IMAGE);
        assertThat(DocumentKind.of("vortrag.mp4", null)).isEqualTo(DocumentKind.VIDEO);
        assertThat(DocumentKind.of("mail.msg", null)).isEqualTo(DocumentKind.EMAIL);
        assertThat(DocumentKind.of("akte.zip", null)).isEqualTo(DocumentKind.ARCHIVE);
        assertThat(DocumentKind.of("notizen.txt", null)).isEqualTo(DocumentKind.TEXT);
        assertThat(DocumentKind.of("ordner/seite.html", null)).isEqualTo(DocumentKind.PAGE);
    }

    @Test
    void withoutAKnownExtensionTheReportedMediaTypeDecides() {
        // BSCW meldet Videos ohne Endung mit ihrem Inhaltstyp (#964).
        assertThat(DocumentKind.of("Aufzeichnung Regeltermin", "video/mp4")).isEqualTo(DocumentKind.VIDEO);
        assertThat(DocumentKind.of("Scan", "image/png; charset=binary")).isEqualTo(DocumentKind.IMAGE);
        assertThat(DocumentKind.of("Protokoll", "application/pdf")).isEqualTo(DocumentKind.PDF);
        assertThat(DocumentKind.of("README", "text/plain")).isEqualTo(DocumentKind.TEXT);
        assertThat(DocumentKind.of("Unbekannt.bin", "application/octet-stream")).isEqualTo(DocumentKind.OTHER);
    }

    @Test
    void aTitleWithoutExtensionAndWithoutOrWithAPageMediaTypeIsAPage() {
        assertThat(DocumentKind.of("Schutzbedarfsfeststellung Datenhaus", null)).isEqualTo(DocumentKind.PAGE);
        assertThat(DocumentKind.of("Kurzbeschreibung Nachtlauf V1.0", "application/json")).isEqualTo(DocumentKind.PAGE);
        assertThat(DocumentKind.of("Release 2.1", null)).isEqualTo(DocumentKind.PAGE);
        assertThat(DocumentKind.of("Seite.unbekannt", "text/html")).isEqualTo(DocumentKind.PAGE);
        assertThat(DocumentKind.of("", "")).isEqualTo(DocumentKind.PAGE);
        assertThat(DocumentKind.of(null, null)).isEqualTo(DocumentKind.PAGE);
    }

    @Test
    void everyKindHasALabelForTheUserInterface() {
        for (DocumentKind kind : DocumentKind.values()) {
            assertThat(kind.label()).isNotBlank();
        }
    }
}
