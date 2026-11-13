package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.BlockSink;
import com.aresstack.spaceharvester.application.assets.EmbeddedEntry;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.PartSink;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.application.assets.StructureSink;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.testcorpus.Archives;
import com.aresstack.spaceharvester.testcorpus.LargeFiles;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;

/** Eigener Tika-Adapter (ADR 0026, AP3) mit kleinen Prüfdateien; große laufen in {@link LargeDocumentFilesTest}. */
class TikaDocumentStructureTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @TempDir
    Path directory;

    private final TikaDocumentStructure structure = new TikaDocumentStructure();

    @Test
    void readsWordFromTheFileAsParagraphBlocksInReadingOrder() {
        Path docx = LargeFiles.docx(directory.resolve("a.docx"), 120, 0);
        Recording sink = new Recording();

        StructuredDocument result = structure.extract(docx, "a.docx", DOCX, ExtractionLimits.DEFAULTS, sink);

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(result.blocks()).isEqualTo(sink.blocks.size());
        assertThat(result.chars()).isEqualTo(sink.blocks.stream().mapToLong(Block::chars).sum());
        assertThat(sink.blocks).allSatisfy(block -> assertThat(block).isInstanceOf(Block.Paragraph.class));
        assertThat(sink.blocks).extracting(Block::index).containsExactlyElementsOf(
                java.util.stream.LongStream.range(0, sink.blocks.size()).boxed().toList());
        String text = sink.text();
        assertThat(text).contains(LargeFiles.paragraphMark(1), LargeFiles.paragraphMark(119));
        assertThat(text.indexOf(LargeFiles.paragraphMark(10))).isLessThan(text.indexOf(LargeFiles.paragraphMark(11)));
        assertThat(sink.closed).isTrue();
        assertThat(result.outcome().extent()).isPresent();
    }

    @Test
    void readsPdfPagesCompletely() {
        Path pdf = LargeFiles.pdfWithText(directory.resolve("a.pdf"), 5);

        Recording sink = new Recording();
        StructuredDocument result = structure.extract(pdf, "a.pdf", "application/pdf", ExtractionLimits.DEFAULTS,
                sink);

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(sink.text()).contains(LargeFiles.pageMark(1), LargeFiles.pageMark(5));
    }

    @Test
    void anEmptyFileIsUnsupportedWithReasonEmpty() throws Exception {
        Path empty = Files.createFile(directory.resolve("leer.pdf"));

        StructuredDocument result = structure.extract(empty, "leer.pdf", "application/pdf", ExtractionLimits.DEFAULTS,
                new Recording());

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.UNSUPPORTED);
        assertThat(result.outcome().reason()).contains(StatusReason.EMPTY);
    }

    @Test
    void bytesWithoutTextAreNeverReportedAsCompletelyRead() throws Exception {
        Path png = directory.resolve("bild.png");
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", png.toFile());

        StructuredDocument result = structure.extract(png, "bild.png", "image/png", ExtractionLimits.DEFAULTS,
                new Recording());

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.UNSUPPORTED);
        assertThat(result.outcome().reason()).contains(StatusReason.NO_TEXT);
    }

    @Test
    void aFormatWithoutParserIsUnsupported() throws Exception {
        Path file = Files.write(directory.resolve("x.bin"), new byte[] {1, 2, 3, 4});

        StructuredDocument result = structure.extract(file, "x.bin", "application/x-gibt-es-nicht",
                ExtractionLimits.DEFAULTS, new Recording());

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.UNSUPPORTED);
        assertThat(result.outcome().reason()).contains(StatusReason.UNSUPPORTED_FORMAT);
    }

    @Test
    void formatsWithoutProofKeepTheMemoryLimitAndOperatorSizeExcludes() throws Exception {
        Path mail = Files.writeString(directory.resolve("m.eml"), "Subject: x\r\n\r\n" + "Text ".repeat(100));
        ExtractionLimits small = limits(Map.of(ExtractionLimits.ANY_TYPE, 100L), 0, 0);

        StructuredDocument limited = structure.extract(mail, "m.eml", "message/rfc822", small, new Recording());
        StructuredDocument tooLarge = structure.extract(mail, "m.eml", "message/rfc822",
                limits(Map.of(), 0, 10), new Recording());

        assertThat(limited.outcome().status()).isEqualTo(ExtractionStatus.EXCLUDED);
        assertThat(limited.outcome().reason()).contains(StatusReason.MEMORY_LIMIT);
        assertThat(tooLarge.outcome().status()).isEqualTo(ExtractionStatus.EXCLUDED);
        assertThat(tooLarge.outcome().reason()).contains(StatusReason.TOO_LARGE);
        assertThat(ExtractionLimits.DEFAULTS.memoryLimit("message/rfc822")).isEqualTo(20L * 1024 * 1024);
        assertThat(ExtractionLimits.DEFAULTS.memoryLimit("application/pdf")).isZero();
    }

    @Test
    void theCharacterLimitCutsJustAboveAndKeepsWhatWasRead() throws Exception {
        Path text = Files.writeString(directory.resolve("t.txt"), "a".repeat(100));
        Map<Long, StructuredDocument> results = new HashMap<>();
        for (long limit : new long[] {99, 100, 101}) {
            results.put(limit, structure.extract(text, "t.txt", "text/plain",
                    limits(Map.of(), limit, 0), new Recording()));
        }

        assertThat(results.get(99L).outcome().status()).isEqualTo(ExtractionStatus.PARTIAL);
        assertThat(results.get(99L).outcome().reason()).contains(StatusReason.TOO_LARGE);
        assertThat(results.get(99L).outcome().truncated()).isTrue();
        assertThat(results.get(99L).chars()).isEqualTo(99);
        assertThat(results.get(99L).outcome().extent().orElseThrow().chars()).isEqualTo(99);
        assertThat(results.get(100L).outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(results.get(101L).outcome().status()).isEqualTo(ExtractionStatus.OK);
    }

    @Test
    void thePackedArchiveStopsAtTheUnpackRatioInsteadOfFillingTheStore() {
        Path zip = Archives.packed(directory.resolve("gepackt.zip"), 4L * 1024 * 1024);
        ExtractionLimits ratio = new ExtractionLimits(0, 0, 10, 1000, 10, 1000, 10, 10_000, Duration.ofSeconds(30),
                Duration.ofMinutes(5), 0, 2, Map.of());
        Counting sink = new Counting();

        StructuredDocument result = structure.extract(zip, "gepackt.zip", Archives.ZIP, ratio, sink);

        assertThat(result.outcome().reason()).contains(StatusReason.UNPACKED_TOO_LARGE);
        assertThat(result.directory()).singleElement().satisfies(entry -> {
            assertThat(entry.outcome().reason()).contains(StatusReason.UNPACKED_TOO_LARGE);
            assertThat(entry.id()).as("Bytes nicht vollständig gelesen").isInstanceOf(
                    com.aresstack.spaceharvester.application.assets.PartId.Position.class);
            assertThat(entry.size().orElseThrow()).isLessThan(4L * 1024 * 1024);
        });
        assertThat(sink.chars + sink.partChars).isZero();
    }

    @Test
    void noBlockLeavesTheAdapterAboveTheBlockSizeAndNothingIsLost() throws Exception {
        String longWord = "x".repeat(25_000);
        String words = "wort ".repeat(5_000);
        Path text = Files.writeString(directory.resolve("lang.txt"), longWord + "\n\n" + words);
        TikaDocumentStructure small = new TikaDocumentStructure(1, 1_000);
        Recording sink = new Recording();

        StructuredDocument result = small.extract(text, "lang.txt", "text/plain", ExtractionLimits.DEFAULTS, sink);

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(sink.blocks).allSatisfy(block -> assertThat(block.chars()).isLessThanOrEqualTo(1_000));
        String joined = sink.blocks.stream().map(DocumentExtractions::plain).collect(Collectors.joining());
        assertThat(joined.replaceAll("\\s", "")).isEqualTo((longWord + words).replaceAll("\\s", ""));
    }

    @Test
    void aStallingParserEndsAtTheIdleLimitWithWhatItRead() throws Exception {
        Path file = Files.writeString(directory.resolve("h.txt"), "egal");
        Parser stalling = new Parser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext context) {
                return Set.of(MediaType.TEXT_PLAIN);
            }

            @Override
            public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
                    throws org.xml.sax.SAXException {
                handler.startDocument();
                handler.startElement("", "p", "p", new org.xml.sax.helpers.AttributesImpl());
                char[] text = "vor dem Hängen".toCharArray();
                handler.characters(text, 0, text.length);
                handler.endElement("", "p", "p");
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                handler.startElement("", "p", "p", new org.xml.sax.helpers.AttributesImpl());
                char[] late = "zu spät".toCharArray();
                handler.characters(late, 0, late.length);
                handler.endElement("", "p", "p");
            }
        };
        TikaDocumentStructure hanging = new TikaDocumentStructure(1, 1_000, stalling);
        ExtractionLimits idle = new ExtractionLimits(0, 0, 0, 0, 0, 0, 10, 10_000, Duration.ofMillis(300),
                Duration.ofMinutes(1), 0, 1, Map.of());
        Recording sink = new Recording();

        long started = System.nanoTime();
        StructuredDocument result = hanging.extract(file, "h.txt", "text/plain", idle, sink);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.PARTIAL);
        assertThat(result.outcome().reason()).contains(StatusReason.TIMEOUT);
        assertThat(result.outcome().truncated()).isTrue();
        assertThat(sink.text()).contains("vor dem Hängen").doesNotContain("zu spät");
    }

    @Test
    void textOfAnOpenParagraphSurvivesAParserFailure() throws Exception {
        Path file = Files.writeString(directory.resolve("f.txt"), "egal");
        TikaDocumentStructure failing = new TikaDocumentStructure(1, 1_000, scripted((handler, metadata) -> {
            handler.startElement("", "p", "p", new org.xml.sax.helpers.AttributesImpl());
            characters(handler, "schon gelesen");
            throw new org.apache.tika.exception.TikaException("kaputt");
        }));
        Recording sink = new Recording();

        StructuredDocument result = failing.extract(file, "f.txt", "text/plain", ExtractionLimits.DEFAULTS, sink);

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.PARTIAL);
        assertThat(result.outcome().reason()).contains(StatusReason.CORRUPT);
        assertThat(sink.text()).contains("schon gelesen");
        assertThat(result.chars()).isEqualTo("schon gelesen".length());
    }

    @Test
    void lineBreaksAndCellTabsCountTowardsTheBlockSize() throws Exception {
        Path file = Files.writeString(directory.resolve("b.txt"), "egal");
        TikaDocumentStructure breaks = new TikaDocumentStructure(1, 100, scripted((handler, metadata) -> {
            handler.startElement("", "p", "p", new org.xml.sax.helpers.AttributesImpl());
            characters(handler, "Anfang");
            for (int i = 0; i < 1_000; i++) {
                handler.startElement("", "br", "br", new org.xml.sax.helpers.AttributesImpl());
                handler.endElement("", "br", "br");
            }
            characters(handler, "Ende");
            handler.endElement("", "p", "p");
            handler.startElement("", "tr", "tr", new org.xml.sax.helpers.AttributesImpl());
            for (int i = 0; i < 1_000; i++) {
                handler.startElement("", "td", "td", new org.xml.sax.helpers.AttributesImpl());
                handler.endElement("", "td", "td");
            }
            characters(handler, "Zelle");
            handler.endElement("", "tr", "tr");
        }));
        Recording sink = new Recording();

        StructuredDocument result = breaks.extract(file, "b.txt", "text/plain", ExtractionLimits.DEFAULTS, sink);

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(sink.blocks).allSatisfy(block -> assertThat(block.chars()).isLessThanOrEqualTo(100));
        assertThat(sink.text()).contains("Anfang", "Ende", "Zelle");
    }

    @Test
    void anUnreadableEmbeddedFileMakesTheResultPartial() throws Exception {
        Path file = Files.writeString(directory.resolve("e.txt"), "egal");
        TikaDocumentStructure embedded = new TikaDocumentStructure(1, 1_000, scripted((handler, metadata) -> {
            handler.startElement("", "p", "p", new org.xml.sax.helpers.AttributesImpl());
            characters(handler, "Mailtext");
            handler.endElement("", "p", "p");
            metadata.add(org.apache.tika.metadata.TikaCoreProperties.EMBEDDED_EXCEPTION, "verschlüsselt");
        }));

        StructuredDocument result = embedded.extract(file, "e.txt", "text/plain", ExtractionLimits.DEFAULTS,
                new Recording());

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.PARTIAL);
        assertThat(result.outcome().reason()).contains(StatusReason.ENTRIES_NOT_READABLE);
        assertThat(result.outcome().truncated()).isTrue();
    }

    @Test
    void waitingForASlotEndsAtTheTimeLimitWhenAWorkerHangs() throws Exception {
        Path file = Files.writeString(directory.resolve("w.txt"), "egal");
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        TikaDocumentStructure hanging = new TikaDocumentStructure(1, 1_000, scripted((handler, metadata) -> {
            // Ignoriert Unterbrechungen wie ein hängender Parser.
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // weiter warten
                }
            }
        }));
        ExtractionLimits quick = new ExtractionLimits(0, 0, 0, 0, 0, 0, 10, 10_000, Duration.ofMillis(200),
                Duration.ofMillis(300), 0, 1, Map.of());
        try {
            hanging.extract(file, "w.txt", "text/plain", quick, new Recording());

            long started = System.nanoTime();
            StructuredDocument second = hanging.extract(file, "w.txt", "text/plain", quick, new Recording());

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(second.outcome().status()).isEqualTo(ExtractionStatus.FAILED);
            assertThat(second.outcome().reason()).contains(StatusReason.TIMEOUT);
        } finally {
            release.countDown();
        }
    }

    @Test
    void tikaArtefactsAreFilteredButSourceTextStays() throws Exception {
        Path file = Files.writeString(directory.resolve("p.txt"), "egal");
        org.xml.sax.helpers.AttributesImpl none = new org.xml.sax.helpers.AttributesImpl();
        TikaDocumentStructure artefacts = new TikaDocumentStructure(1, 1_000, scripted((handler, metadata) -> {
            handler.startElement("", "p", "p", none);
            characters(handler, "Quelltext");
            org.xml.sax.helpers.AttributesImpl empty = new org.xml.sax.helpers.AttributesImpl();
            handler.startElement("", "a", "a", empty);
            handler.endElement("", "a", "a");
            handler.endElement("", "p", "p");
            handler.startElement("", "p", "p", none);
            handler.endElement("", "p", "p");
            org.xml.sax.helpers.AttributesImpl entry = new org.xml.sax.helpers.AttributesImpl();
            entry.addAttribute("", "class", "class", "CDATA", "package-entry");
            handler.startElement("", "div", "div", entry);
            handler.startElement("", "h1", "h1", none);
            characters(handler, "image1.png");
            handler.endElement("", "h1", "h1");
            handler.endElement("", "div", "div");
            handler.startElement("", "h1", "h1", none);
            characters(handler, "image2.png ist auch ein echter Titel");
            handler.endElement("", "h1", "h1");
        }));
        Recording sink = new Recording();

        artefacts.extract(file, "p.txt", "text/plain", ExtractionLimits.DEFAULTS, sink);

        assertThat(sink.blocks).hasSize(2);
        assertThat(sink.blocks.get(0)).isInstanceOfSatisfying(Block.Paragraph.class, paragraph -> {
            assertThat(paragraph.text()).isEqualTo("Quelltext");
            assertThat(paragraph.links()).as("Anker ohne Ziel").isEmpty();
        });
        assertThat(sink.blocks.get(1)).as("Überschrift der Quelle bleibt").isInstanceOfSatisfying(
                Block.Heading.class, heading -> assertThat(heading.text()).startsWith("image2.png"));
    }

    @Test
    void theMemoryLimitAlsoAppliesToTheTypeFoundInTheFile() throws Exception {
        Path mail = Files.writeString(directory.resolve("bericht.pdf"), "From: a@example.org\r\nTo: b@example.org\r\n"
                + "Subject: Bericht\r\nMIME-Version: 1.0\r\nContent-Type: text/plain\r\n\r\n" + "Text ".repeat(400));
        ExtractionLimits small = limits(Map.of(ExtractionLimits.ANY_TYPE, 1_000L, "application/pdf", 0L), 0, 0);

        StructuredDocument result = structure.extract(mail, "bericht.pdf", "application/pdf", small, new Recording());

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.EXCLUDED);
        assertThat(result.outcome().reason()).contains(StatusReason.MEMORY_LIMIT);
    }

    @Test
    void anEmbeddedFileAboveItsMemoryLimitIsExcludedAndTheContainerStaysReadable() throws Exception {
        Path pdf = pdfWithAttachment(directory.resolve("mit-mail.pdf"), "nachricht.eml",
                ("From: a@example.org\r\nTo: b@example.org\r\nSubject: Anhang\r\nMIME-Version: 1.0\r\n"
                        + "Content-Type: text/plain\r\n\r\nMARKE-EINGEBETTET\r\n").getBytes(StandardCharsets.UTF_8));
        Recording guarded = new Recording();
        Recording open = new Recording();

        StructuredDocument large = structure.extract(pdf, "mit-mail.pdf", "application/pdf",
                limits(Map.of(ExtractionLimits.ANY_TYPE, 100L, "application/pdf", 0L), 0, 0), guarded);
        StructuredDocument small = structure.extract(pdf, "mit-mail.pdf", "application/pdf",
                limits(Map.of(ExtractionLimits.ANY_TYPE, 100L * 1024 * 1024, "application/pdf", 0L), 0, 0), open);

        // Die Schranke gilt je Teil (AP7): Nur der Teil endet, der Behälter bleibt vollständig.
        assertThat(large.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(large.directory()).singleElement().satisfies(entry -> {
            assertThat(entry.outcome().status()).isEqualTo(ExtractionStatus.EXCLUDED);
            assertThat(entry.outcome().reason()).contains(StatusReason.MEMORY_LIMIT);
            assertThat(entry.displayName()).contains("nachricht.eml");
        });
        assertThat(guarded.text()).contains("MARKE-SEITE").doesNotContain("MARKE-EINGEBETTET");
        assertThat(guarded.partText()).isEmpty();
        assertThat(small.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(small.directory()).singleElement().satisfies(entry ->
                assertThat(entry.outcome().status()).isEqualTo(ExtractionStatus.OK));
        assertThat(open.text()).as("nie im Strom des Behälters").contains("MARKE-SEITE")
                .doesNotContain("MARKE-EINGEBETTET");
        assertThat(open.partText("nachricht.eml")).contains("MARKE-EINGEBETTET");
    }

    private static Path pdfWithAttachment(Path target, String name, byte[] content) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream text =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                text.beginText();
                text.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 10);
                text.newLineAtOffset(40, 700);
                text.showText("MARKE-SEITE");
                text.endText();
            }
            org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile file =
                    new org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(document,
                            new java.io.ByteArrayInputStream(content));
            file.setSubtype("message/rfc822");
            file.setSize(content.length);
            org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification spec =
                    new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification();
            spec.setFile(name);
            spec.setEmbeddedFile(file);
            org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode tree =
                    new org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of(name, spec));
            org.apache.pdfbox.pdmodel.PDDocumentNameDictionary names =
                    new org.apache.pdfbox.pdmodel.PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            document.getDocumentCatalog().setNames(names);
            document.save(target.toFile());
        }
        return target;
    }

    @FunctionalInterface
    interface Script {
        void run(ContentHandler handler, Metadata metadata) throws Exception;
    }

    private static Parser scripted(Script script) {
        return new Parser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext context) {
                return Set.of(MediaType.TEXT_PLAIN);
            }

            @Override
            public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
                    throws org.xml.sax.SAXException, org.apache.tika.exception.TikaException {
                try {
                    handler.startDocument();
                    script.run(handler, metadata);
                    handler.endDocument();
                } catch (org.xml.sax.SAXException | org.apache.tika.exception.TikaException
                         | RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new org.apache.tika.exception.TikaException("Skript", e);
                }
            }
        };
    }

    private static void characters(ContentHandler handler, String text) throws org.xml.sax.SAXException {
        char[] chars = text.toCharArray();
        handler.characters(chars, 0, chars.length);
    }

    private static ExtractionLimits limits(Map<String, Long> memory, long maxChars, long maxFileBytes) {
        return new ExtractionLimits(maxFileBytes, maxChars, 100, 100L * 1024 * 1024, 100, 1_000_000, 10, 10_000,
                Duration.ofSeconds(30), Duration.ofMinutes(5), 0, 2, memory);
    }

    /** Hält die Blöcke kleiner Dokumente fest. */
    static final class Recording implements StructureSink {

        final List<Block> blocks = new ArrayList<>();
        boolean closed;

        @Override
        public BlockSink document() {
            return new BlockSink() {
                @Override
                public void accept(Block block) {
                    blocks.add(block);
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }

        /** Enthaltene Dateien mit ihren Blöcken, in der Reihenfolge ihres Beginns. */
        final java.util.Map<EmbeddedEntry, List<Block>> parts = new java.util.LinkedHashMap<>();
        final java.util.Map<EmbeddedEntry, DocumentHead> heads = new java.util.HashMap<>();
        int openParts;

        @Override
        public PartSink part(EmbeddedEntry entry) {
            List<Block> partBlocks = new ArrayList<>();
            parts.put(entry, partBlocks);
            openParts++;
            return new PartSink() {
                @Override
                public void accept(Block block) {
                    partBlocks.add(block);
                }

                @Override
                public void head(DocumentHead head) {
                    heads.put(entry, head);
                }

                @Override
                public void close() {
                    openParts--;
                }
            };
        }

        /** Nur der Text des äußeren Dokuments. */
        String text() {
            return blocks.stream().map(DocumentExtractions::plain).collect(Collectors.joining("\n"));
        }

        /** Der Text aller enthaltenen Dateien. */
        String partText() {
            return parts.values().stream().flatMap(List::stream).map(DocumentExtractions::plain)
                    .collect(Collectors.joining("\n"));
        }

        /** Text des Teils, dessen Anzeigename passt. */
        String partText(String displayName) {
            return parts.entrySet().stream().filter(e -> e.getKey().displayName().filter(displayName::equals)
                            .isPresent()).flatMap(e -> e.getValue().stream()).map(DocumentExtractions::plain)
                    .collect(Collectors.joining("\n"));
        }
    }

    /** Zählt nur, für große Dokumente. */
    static final class Counting implements StructureSink {

        long blocks;
        long chars;
        final Set<String> found = new java.util.HashSet<>();
        private final Set<String> marks;
        long largestBlock;

        Counting(String... marks) {
            this.marks = Set.of(marks);
        }

        @Override
        public BlockSink document() {
            return block -> {
                blocks++;
                chars += block.chars();
                largestBlock = Math.max(largestBlock, block.chars());
                String text = DocumentExtractions.plain(block);
                for (String mark : marks) {
                    if (text.contains(mark)) {
                        found.add(mark);
                    }
                }
            };
        }

        final List<EmbeddedEntry> entries = new ArrayList<>();
        long partChars;

        @Override
        public PartSink part(EmbeddedEntry entry) {
            entries.add(entry);
            return block -> partChars += block.chars();
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
