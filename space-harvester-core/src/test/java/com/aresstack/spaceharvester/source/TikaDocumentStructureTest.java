package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Block;
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
    void thePackedArchiveStopsAtTheTextRatioInsteadOfFillingTheStore() {
        Path zip = Archives.packed(directory.resolve("gepackt.zip"), 4L * 1024 * 1024);
        ExtractionLimits ratio = new ExtractionLimits(0, 0, 0, 0, 10, 1000, 10, 10_000, Duration.ofSeconds(30),
                Duration.ofMinutes(5), 0, 2, Map.of());
        Counting sink = new Counting();

        StructuredDocument result = structure.extract(zip, "gepackt.zip", Archives.ZIP, ratio, sink);

        assertThat(result.outcome().truncated()).isTrue();
        assertThat(result.outcome().reason().orElseThrow()).isIn(StatusReason.TEXT_RATIO_EXCEEDED,
                StatusReason.UNPACKED_TOO_LARGE);
        assertThat(sink.chars).isLessThan(4L * 1024 * 1024);
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
        String joined = sink.blocks.stream().map(b -> ((Block.Paragraph) b).text()).collect(Collectors.joining());
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

        @Override
        public PartSink part(EmbeddedEntry entry) {
            throw new AssertionError("Enthaltene Dateien erst mit AP7");
        }

        String text() {
            return blocks.stream().map(b -> ((Block.Paragraph) b).text()).collect(Collectors.joining("\n"));
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
                String text = ((Block.Paragraph) block).text();
                for (String mark : marks) {
                    if (text.contains(mark)) {
                        found.add(mark);
                    }
                }
            };
        }

        @Override
        public PartSink part(EmbeddedEntry entry) {
            throw new AssertionError("Enthaltene Dateien erst mit AP7");
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
