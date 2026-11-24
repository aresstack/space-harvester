package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.Location;
import com.aresstack.spaceharvester.application.assets.NotePageLocation;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OneNoteDocumentStructureTest {
    @TempDir
    Path directory;

    @Test
    void identifyOneNoteForLocalDiscoveryAndDocumentText() throws IOException {
        Path original = fixture();
        byte[] bytes = Files.readAllBytes(original);
        OneNoteContentDetector detector = new OneNoteContentDetector(DocumentExtraction.detector());
        assertThat(detector.detect("notes.one", null, null).category()).isEqualTo(ContentCategory.OFFICE_DOCUMENT);
        assertThat(detector.detect(null, null, bytes).category()).isEqualTo(ContentCategory.OFFICE_DOCUMENT);
        assertThat(LocalFilesTextProjector.readable(OneNoteContentDetector.SECTION_MIME)).isTrue();
        assertThat(TikaDocumentText.shared().extract("notes.one", OneNoteContentDetector.SECTION_MIME, bytes).text())
                .contains("Section1Page1Content", "Section1Page2Content");
    }

    @Test
    void retainExplicitPageBoundariesFromRealOneNoteDocument() throws IOException {
        Path original = fixture();
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();
        StructuredDocument result = new TikaDocumentStructure().extract(original, "notes.one",
                OneNoteContentDetector.SECTION_MIME, fixtureLimits(), sink);

        assertThat(result.outcome().status()).as(result.outcome().toString()).isEqualTo(ExtractionStatus.OK);
        assertThat(notePageOf(sink, "Section1Page1Content")).contains(new NotePageLocation(1));
        assertThat(notePageOf(sink, "Section1Page2Content")).contains(new NotePageLocation(2));
        assertThat(sink.blocks).allSatisfy(block -> {
            assertThat(block.location().flatMap(Location::page)).isEmpty();
            assertThat(block.location().flatMap(Location::sheet)).isEmpty();
        });
    }

    @Test
    void previewSeparatesVerifiedPages() throws IOException {
        var preview = new OneNotePreviewReader(new TikaDocumentStructure(), fixtureLimits())
                .read("notes.one", fixture());
        assertThat(preview.hasPageBoundaries()).isTrue();
        assertThat(preview.sections()).hasSize(2);
        assertThat(preview.sections().get(0).text()).contains("Section1Page1Content")
                .doesNotContain("Section1Page2Content");
        assertThat(preview.sections().get(1).text()).contains("Section1Page2Content")
                .doesNotContain("Section1Page1Content");
    }

    @Test
    void olderSectionWithoutPageContainersRetainsTextWithoutInventingPages() throws IOException {
        Path original = directory.resolve("legacy.one");
        try (InputStream input = getClass().getResourceAsStream("/onenote/testOneNote1.one")) {
            if (input == null) {
                throw new IOException("Legacy OneNote regression fixture not found");
            }
            Files.copy(input, original);
        }
        var preview = new OneNotePreviewReader(new TikaDocumentStructure(), fixtureLimits())
                .read("legacy.one", original);
        assertThat(preview.sections()).isNotEmpty();
        assertThat(preview.hasPageBoundaries()).isFalse();
        assertThat(preview.sections()).allSatisfy(section -> assertThat(section.location()).isEmpty());
        assertThat(preview.sections().stream().map(section -> section.text()).toList())
                .anySatisfy(text -> assertThat(text).contains("OneNote Basics"));
    }

    private static ExtractionLimits fixtureLimits() {
        ExtractionLimits defaults = ExtractionLimits.DEFAULTS;
        return new ExtractionLimits(defaults.maxFileBytes(), defaults.maxChars(), defaults.unpackRatio(),
                defaults.unpackRatioFromBytes(), defaults.textRatio(), defaults.textRatioFromChars(),
                defaults.maxDepth(), defaults.maxEmbeddedFiles(), defaults.idleTimeout(), defaults.totalTimeout(),
                0, defaults.parallel(), defaults.memoryLimits());
    }

    private static Optional<NotePageLocation> notePageOf(TikaDocumentStructureTest.Recording sink, String text) {
        return sink.blocks.stream().filter(block -> block instanceof Block.Paragraph p && p.text().contains(text))
                .findFirst().flatMap(Block::location).flatMap(Location::notePage);
    }

    private Path fixture() throws IOException {
        Path target = directory.resolve("notes.one");
        try (InputStream input = getClass().getResourceAsStream("/onenote/testOneNoteFromOffice365.one")) {
            if (input == null) {
                throw new IOException("OneNote regression fixture not found");
            }
            Files.copy(input, target);
        }
        return target;
    }
}

