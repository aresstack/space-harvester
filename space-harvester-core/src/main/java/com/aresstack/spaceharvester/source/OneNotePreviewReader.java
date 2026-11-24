package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.assets.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read a preview through the existing structured document adapter. */
public final class OneNotePreviewReader {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long MAX_CHARS = 1_000_000;
    private final DocumentStructure structure;
    private final ExtractionLimits limits;

    public OneNotePreviewReader(DocumentStructure structure, ExtractionLimits limits) {
        this.structure = Objects.requireNonNull(structure, "structure");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    private static final class Shared {
        private static final OneNotePreviewReader INSTANCE =
                new OneNotePreviewReader(new TikaDocumentStructure(), previewLimits());
    }

    public static OneNotePreviewReader shared() {
        return Shared.INSTANCE;
    }

    private static ExtractionLimits previewLimits() {
        ExtractionLimits d = ExtractionLimits.DEFAULTS;
        return new ExtractionLimits(MAX_BYTES, MAX_CHARS, d.unpackRatio(), d.unpackRatioFromBytes(),
                d.textRatio(), d.textRatioFromChars(), d.maxDepth(), d.maxEmbeddedFiles(),
                d.idleTimeout(), d.totalTimeout(), d.tempSpaceReserve(), d.parallel(), d.memoryLimits());
    }

    public OneNotePreview read(String title, byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) {
            throw new IOException("OneNote preview exceeds the 20 MiB memory limit");
        }
        Path original = Files.createTempFile("onenote-preview-", ".one");
        try {
            Files.write(original, bytes);
            return read(title, original);
        } finally {
            Files.deleteIfExists(original);
        }
    }

    public OneNotePreview read(String title, Path original) {
        List<Block> blocks = new ArrayList<>();
        StructureSink sink = new StructureSink() {
            @Override
            public BlockSink document() {
                return blocks::add;
            }

            @Override
            public PartSink part(EmbeddedEntry entry) {
                return block -> {};
            }

            @Override
            public boolean restart() {
                blocks.clear();
                return true;
            }
        };
        StructuredDocument document = structure.extract(original, title,
                OneNoteContentDetector.SECTION_MIME, limits, sink);
        return OneNotePreview.fromBlocks(blocks, document.outcome());
    }
}
