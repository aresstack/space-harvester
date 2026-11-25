package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.StoredExtraction;
import com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.util.Objects;
import java.util.stream.Stream;

/** Index every retained section of a local original as the page itself. */
public final class LocalPageSections {

    private final DocumentExtractions documents;

    public LocalPageSections(DocumentExtractions documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    public boolean supports(PageSnapshot snapshot) {
        return LocalOriginalReference.recognizes(snapshot.payload());
    }

    public boolean index(PageSnapshot snapshot, PageMetadata metadata, PageTextIndex index) {
        LocalOriginalReference reference = LocalOriginalReference.read(snapshot.payload()).orElseThrow();
        String input = PageTextIndexing.entryState(snapshot.payload().rawSha256(), metadata);
        String mediaType = reference.originalMediaType();
        if (index.inputSha256(snapshot.key()).filter(input::equals).isPresent()) {
            return false;
        }
        if (reference.extractionKey() == null) {
            index.replacePageSections(snapshot.key(), java.util.List.of(new PageTextIndex.Entry(snapshot.key(),
                    metadata.partitionKey(), metadata.title(), "", input, mediaType)));
            return true;
        }
        // Missing or damaged extraction must not replace the previous searchable state with a preview.
        StoredExtraction extraction = documents.stored(reference.extractionKey())
                .orElseThrow(() -> new IllegalStateException("Referenced extraction missing"));
        try (Stream<DocumentExtractions.IndexSection> sections = documents.indexSections(extraction)) {
            Stream<PageTextIndex.Entry> entries = sections.flatMap(section -> split(section.text())
                    .map(text -> new PageTextIndex.Entry(snapshot.key(), metadata.partitionKey(),
                            metadata.title(), text, input, mediaType)));
            // Keep an entry for title searches even for a document without text.
            try (Stream<PageTextIndex.Entry> all = Stream.concat(entries, Stream.of(new PageTextIndex.Entry(
                    snapshot.key(), metadata.partitionKey(), metadata.title(), "", input, mediaType)))) {
                index.replacePageSections(snapshot.key(), all::iterator);
            }
        }
        return true;
    }

    private static Stream<String> split(String text) {
        // Bound each Lucene entry even when the extractor emitted a single unusually large block.
        final int size = 100_000;
        return java.util.stream.IntStream.iterate(0, offset -> offset < text.length(), offset -> offset + size)
                .mapToObj(offset -> text.substring(offset, Math.min(offset + size, text.length())));
    }
}
