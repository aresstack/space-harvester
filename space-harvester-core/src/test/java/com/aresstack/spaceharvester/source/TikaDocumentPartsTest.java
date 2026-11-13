package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.EmbeddedEntry;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.assets.PartRules;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.testcorpus.Archives;
import com.aresstack.spaceharvester.testcorpus.Corpus;
import com.aresstack.spaceharvester.testcorpus.LargeFiles;
import com.aresstack.spaceharvester.testcorpus.Limits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Enthaltene Dateien als eigene Einträge (ADR 0026, AP7a; Abnahme 3, 4, 7 und 11). */
class TikaDocumentPartsTest {

    private static final String MAIL = "message/rfc822";

    private final TikaDocumentStructure structure = new TikaDocumentStructure();

    @TempDir
    Path directory;

    @Test
    void attachmentsOfAMailAreEntriesAndNeverTextOfTheMail() throws IOException {
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument mail = read("zip.eml", Corpus.zipInMail().bytes(), ExtractionLimits.DEFAULTS, sink);

        assertThat(mail.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(sink.text()).contains(Corpus.MAIL_BODY_MARK).doesNotContain("MARKE-ZIP", "hinweis.txt",
                "unterlagen.zip");
        EmbeddedEntry zip = entry(mail, "unterlagen.zip");
        assertThat(zip.depth()).isEqualTo(1);
        assertThat(zip.parent()).isEmpty();
        assertThat(zip.id()).isInstanceOf(PartId.Content.class);
        assertThat(zip.position()).isEqualTo("/1");
        for (String name : List.of("hinweis.txt", "bericht.docx")) {
            EmbeddedEntry inner = entry(mail, name);
            assertThat(inner.depth()).isEqualTo(2);
            assertThat(inner.parent()).contains(zip.id());
            assertThat(inner.outcome().status()).isEqualTo(ExtractionStatus.OK);
        }
        assertThat(sink.partText("hinweis.txt")).contains("MARKE-ZIP-TXT").doesNotContain("hinweis.txt");
        assertThat(sink.partText("bericht.docx")).contains("MARKE-ZIP-DOCX");
        assertThat(sink.partText("unterlagen.zip")).as("ein Archiv hat keinen eigenen Text").isEmpty();
        assertThat(sink.openParts).isZero();
    }

    @Test
    void sameNamesAreTwoEntriesSameContentSharesTheHashAndOrderDoesNotChangeIds() throws IOException {
        StructuredDocument forward = read("doppelt.eml", Corpus.duplicateNames(false).bytes(),
                ExtractionLimits.DEFAULTS, new TikaDocumentStructureTest.Recording());
        StructuredDocument reversed = read("vertauscht.eml", Corpus.duplicateNames(true).bytes(),
                ExtractionLimits.DEFAULTS, new TikaDocumentStructureTest.Recording());

        assertThat(forward.directory()).hasSize(6);
        assertThat(forward.directory().stream().filter(e -> e.displayName().filter("notiz.txt"::equals).isPresent()))
                .hasSize(2).extracting(EmbeddedEntry::id).doesNotHaveDuplicates();
        EmbeddedEntry a = entry(forward, "kopie-a.txt");
        EmbeddedEntry b = entry(forward, "kopie-b.txt");
        assertThat(a.sha256()).isEqualTo(b.sha256());
        assertThat(List.of(((PartId.Content) a.id()).ordinal(), ((PartId.Content) b.id()).ordinal()))
                .containsExactly(1, 2);
        assertThat(entry(forward, "../flucht.txt").displayName()).as("Pfadzeichen bleiben Anzeige")
                .contains("../flucht.txt");
        // Vertauschte Reihenfolge: dieselben Kennungen (nur bei inhaltsgleichen kann die Nummer wandern).
        assertThat(reversed.directory()).extracting(EmbeddedEntry::id)
                .containsExactlyInAnyOrderElementsOf(forward.directory().stream().map(EmbeddedEntry::id).toList());
    }

    @Test
    void aForwardedMailIsAnEntryWithItsOwnHeadAndAttachments() throws IOException {
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument mail = read("weiter.eml", Corpus.mailInMail().bytes(), ExtractionLimits.DEFAULTS, sink);

        EmbeddedEntry forwarded = mail.directory().getFirst();
        assertThat(forwarded.mediaType()).contains(MAIL);
        assertThat(forwarded.displayName()).isEmpty();
        assertThat(sink.heads.get(sink.parts.keySet().stream().filter(e -> e.id().equals(forwarded.id()))
                .findFirst().orElseThrow()).subject()).contains("Protokoll der Lenkungsrunde");
        assertThat(entry(mail, "protokoll.rtf").parent()).contains(forwarded.id());
        assertThat(entry(mail, "kennzahlen.xlsx").parent()).contains(forwarded.id());
        assertThat(sink.text()).doesNotContain("MARKE-INNEN");
        assertThat(sink.partText()).contains("MARKE-INNEN ", "MARKE-INNEN-RTF", "MARKE-INNEN-XLSX");
        assertThat(mail.head().subject()).contains("WG: Protokoll der Lenkungsrunde");
    }

    @Test
    void anOutlookMessageInAMailIsOpenedWithItsAttachments() throws IOException {
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument mail = read("outlook.eml", Corpus.outlookMailInMail().bytes(), ExtractionLimits.DEFAULTS,
                sink);

        EmbeddedEntry msg = entry(mail, "weiter.msg");
        assertThat(entry(mail, "anlage.docx").parent()).contains(msg.id());
        assertThat(entry(mail, "notiz.txt").parent()).contains(msg.id());
        assertThat(sink.partText("anlage.docx")).contains("MARKE-MSG-DOCX");
        assertThat(sink.partText("weiter.msg")).contains("MARKE-MSG-TEXT").doesNotContain("MARKE-MSG-DOCX");
    }

    @Test
    void damagedPartsCarryTheirStatusAndTheHealthyOneIsRead() throws IOException {
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument mail = read("defekt.eml", Corpus.damaged().bytes(), ExtractionLimits.DEFAULTS, sink);

        assertThat(entry(mail, "kaputt.docx").outcome().status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(entry(mail, "kaputt.pdf").outcome().status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(entry(mail, "leer.pdf").outcome().reason()).contains(StatusReason.EMPTY);
        assertThat(entry(mail, "geheim.pdf").outcome().reason()).contains(StatusReason.ENCRYPTED);
        assertThat(entry(mail, "geheim.docx").outcome().reason()).contains(StatusReason.ENCRYPTED);
        EmbeddedEntry zip = entry(mail, "teils-geheim.zip");
        assertThat(zip.outcome().reason()).as("der verschlüsselte Eintrag kommt nicht als Datei")
                .contains(StatusReason.ENTRIES_NOT_READABLE);
        assertThat(mail.directory().stream().filter(e -> e.parent().equals(Optional.of(zip.id())))).hasSize(2);
        assertThat(entry(mail, "liesmich.txt").outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(sink.partText("liesmich.txt")).contains("MARKE-GESUND");
        String all = sink.text() + sink.partText();
        assertThat(all).as("kein erzeugter Text, keine Dateinamen").doesNotContain("geheim.txt", "MARKE-DEFEKT",
                "MARKE-GEHEIM", "kaputt", "liesmich");
        assertThat(mail.outcome().status()).isEqualTo(ExtractionStatus.OK);
    }

    @Test
    void theNumberOfEntriesStopsAtTheLimitWithoutFurtherEntries() throws IOException {
        int limit = 3;
        for (int count : Limits.around(limit)) {
            StructuredDocument mail = read("viele-" + count + ".eml", Corpus.manyAttachments(count).bytes(),
                    limits(10, limit), new TikaDocumentStructureTest.Recording());

            assertThat(mail.directory()).as("%d Anhänge", count).hasSize(Math.min(count, limit));
            if (count > limit) {
                assertThat(mail.outcome().status()).isEqualTo(ExtractionStatus.PARTIAL);
                assertThat(mail.outcome().reason()).contains(StatusReason.TOO_MANY_FILES);
            } else {
                assertThat(mail.outcome().status()).isEqualTo(ExtractionStatus.OK);
            }
        }
    }

    @Test
    void tooDeepPartsGetAnEntryButAreNotOpened() throws IOException {
        int limit = 2;
        for (int depth : Limits.around(limit)) {
            // depth Mails ineinander: depth - 1 Ebenen enthaltener Mails.
            TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();
            StructuredDocument mail = read("tief-" + depth + ".eml", Corpus.nestedMails(depth + 1).bytes(),
                    limits(limit, 10_000), sink);

            assertThat(mail.directory()).as("Tiefe %d", depth).hasSize(Math.min(depth, limit + 1));
            if (depth > limit) {
                EmbeddedEntry deepest = mail.directory().stream().filter(e -> e.depth() == limit + 1).findFirst()
                        .orElseThrow();
                assertThat(deepest.outcome().reason()).contains(StatusReason.TOO_DEEP);
                assertThat(deepest.id()).isInstanceOf(PartId.Position.class);
                assertThat(sink.partText()).doesNotContain(Archives.levelMark(limit + 2));
            } else {
                assertThat(mail.directory()).allSatisfy(e ->
                        assertThat(e.outcome().status()).isEqualTo(ExtractionStatus.OK));
                assertThat(sink.partText()).contains(Archives.levelMark(depth + 1));
            }
        }
    }

    @Test
    void typesThatAreNoDocumentsAreExcludedButArchivesAreStillOpened() throws IOException {
        Path file = Files.write(directory.resolve("zip.eml"), Corpus.zipInMail().bytes());
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument mail = structure.extract(file, "zip.eml", MAIL, ExtractionLimits.DEFAULTS, sink,
                new PartRules(Optional.empty(), "text/plain"::equals));

        assertThat(entry(mail, "unterlagen.zip").outcome().status()).isNotEqualTo(ExtractionStatus.EXCLUDED);
        assertThat(entry(mail, "hinweis.txt").outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(entry(mail, "bericht.docx").outcome().reason()).contains(StatusReason.MEDIA_TYPE_EXCLUDED);
        assertThat(sink.partText()).contains("MARKE-ZIP-TXT").doesNotContain("MARKE-ZIP-DOCX");
    }

    @Test
    void inlinePicturesBelongToTheDocument() {
        Path docx = LargeFiles.docx(directory.resolve("bilder.docx"), 3, 2);

        StructuredDocument result = structure.extract(docx, "bilder.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ExtractionLimits.DEFAULTS,
                new TikaDocumentStructureTest.Recording());

        assertThat(result.directory()).isEmpty();
    }

    @Test
    void bothVersionsOfAnAlternativeMailGiveItsTextOnce() throws IOException {
        String alternative = "From: a@example.org\r\nTo: b@example.org\r\nSubject: Alt\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"A\"\r\n\r\n"
                + "--A\r\nContent-Type: text/plain\r\n\r\nMARKE-ALT nur einmal\r\n"
                + "--A\r\nContent-Type: text/html\r\n\r\n<html><body><p>MARKE-ALT nur einmal</p></body></html>\r\n"
                + "--A--\r\n";
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument mail = read("alt.eml", alternative.getBytes(StandardCharsets.UTF_8),
                ExtractionLimits.DEFAULTS, sink);

        assertThat(mail.directory()).isEmpty();
        assertThat(sink.text().split("MARKE-ALT", -1)).hasSize(2);
    }

    @Test
    void temporaryFilesAreRemovedAfterEveryPart() throws IOException {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> before = parts(temp);

        read("defekt.eml", Corpus.damaged().bytes(), ExtractionLimits.DEFAULTS,
                new TikaDocumentStructureTest.Recording());
        // Abbruch an der Zeichengrenze mitten in einem Teil.
        read("lang.eml", Corpus.longParts(2, 5_000).bytes(), new ExtractionLimits(0, 1_000, 100, 100L << 20, 100,
                1_000_000, 10, 10_000, java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(30), 0, 2,
                Map.of()), new TikaDocumentStructureTest.Recording());

        assertThat(parts(temp)).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void aPartEndsAtItsOwnCharacterLimitAndTheOthersAreRead() throws IOException {
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument mail = read("lang.eml", Corpus.longParts(2, 5_000).bytes(), new ExtractionLimits(0, 8_000,
                100, 100L << 20, 100, 1_000_000, 10, 10_000, java.time.Duration.ofSeconds(30),
                java.time.Duration.ofMinutes(30), 0, 2, Map.of()), sink);

        assertThat(mail.directory()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.outcome().status()).isEqualTo(ExtractionStatus.OK);
        });
        assertThat(sink.partText()).contains("ENDE-ANHANG-0", "ENDE-ANHANG-1");
        assertThat(sink.text()).contains("ENDE-MAIL");

        TikaDocumentStructureTest.Recording small = new TikaDocumentStructureTest.Recording();
        StructuredDocument cut = read("lang.eml", Corpus.longParts(2, 5_000).bytes(), new ExtractionLimits(0, 1_000,
                100, 100L << 20, 100, 1_000_000, 10, 10_000, java.time.Duration.ofSeconds(30),
                java.time.Duration.ofMinutes(30), 0, 2, Map.of()), small);

        assertThat(cut.outcome().reason()).as("die Mail selbst gekürzt").contains(StatusReason.TOO_LARGE);
        assertThat(cut.directory()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.outcome().status()).isEqualTo(ExtractionStatus.PARTIAL);
            assertThat(entry.outcome().reason()).contains(StatusReason.TOO_LARGE);
        });
    }

    @Test
    void aContainerWithoutOwnTextButWithEntriesIsReadOnlyAnEmptyOneHasNoText() throws IOException {
        Path archive = Files.write(directory.resolve("archiv.zip"), Archives.nested(2));
        StructuredDocument read = structure.extract(archive, "archiv.zip", Archives.ZIP, ExtractionLimits.DEFAULTS,
                new TikaDocumentStructureTest.Recording());

        assertThat(read.chars()).isZero();
        assertThat(read.directory()).isNotEmpty();
        assertThat(read.outcome().status()).isEqualTo(ExtractionStatus.OK);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("leer/"));
            zip.closeEntry();
        }
        Path empty = Files.write(directory.resolve("leer.zip"), bytes.toByteArray());
        StructuredDocument none = structure.extract(empty, "leer.zip", Archives.ZIP, ExtractionLimits.DEFAULTS,
                new TikaDocumentStructureTest.Recording());

        assertThat(none.directory()).isEmpty();
        assertThat(none.outcome().status()).isEqualTo(ExtractionStatus.UNSUPPORTED);
        assertThat(none.outcome().reason()).contains(StatusReason.NO_TEXT);
    }

    private static List<Path> parts(Path temp) throws IOException {
        try (Stream<Path> files = Files.list(temp)) {
            return files.filter(path -> path.getFileName().toString().startsWith("teil-")).toList();
        }
    }

    private StructuredDocument read(String name, byte[] bytes, ExtractionLimits limits,
                                    TikaDocumentStructureTest.Recording sink) throws IOException {
        Path file = Files.write(directory.resolve(name), bytes);
        return structure.extract(file, name, MAIL, limits, sink);
    }

    private static EmbeddedEntry entry(StructuredDocument document, String name) {
        return document.directory().stream().filter(e -> e.displayName().filter(name::equals).isPresent())
                .findFirst().orElseThrow(() -> new AssertionError("kein Eintrag " + name));
    }

    private static ExtractionLimits limits(int depth, int files) {
        ExtractionLimits d = ExtractionLimits.DEFAULTS;
        return new ExtractionLimits(d.maxFileBytes(), d.maxChars(), d.unpackRatio(), d.unpackRatioFromBytes(),
                d.textRatio(), d.textRatioFromChars(), depth, files, d.idleTimeout(), d.totalTimeout(),
                d.tempSpaceReserve(), d.parallel(), d.memoryLimits());
    }
}
