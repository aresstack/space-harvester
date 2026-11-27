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
        if (index.inputSha256(snapshot.key()).filter(input::equals).isPresent()
                && index.contentStateKnown(snapshot.key()) && index.headingsKnown(snapshot.key())) {
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
            Stream<PageTextIndex.Entry> entries = sections.flatMap(section ->
                    entries(snapshot, metadata, input, mediaType, section));
            // Retain a title-only entry only when the document has no indexable sections.
            try (entries) {
                var iterator = entries.iterator();
                if (iterator.hasNext()) {
                    index.replacePageSections(snapshot.key(), () -> iterator);
                } else {
                    index.replacePageSections(snapshot.key(), java.util.List.of(new PageTextIndex.Entry(
                            snapshot.key(), metadata.partitionKey(), metadata.title(), "", input, mediaType)));
                }
            }
        }
        return true;
    }

    private static Stream<PageTextIndex.Entry> entries(PageSnapshot snapshot, PageMetadata metadata, String input,
                                                        String mediaType, DocumentExtractions.IndexSection section) {
        // Keep embedded names searchable without counting them as extracted document content.
        String prefix = section.partTitle().isBlank() ? "" : section.partTitle() + "\n";
        String text = prefix + section.text();
        final int size = 100_000;
        return java.util.stream.IntStream.iterate(0, offset -> offset < text.length(), offset -> offset + size)
                .mapToObj(offset -> {
                    String chunk = text.substring(offset, Math.min(offset + size, text.length()));
                    int nameChars = Math.min(chunk.length(), Math.max(0, prefix.length() - offset));
                    // Überschriften gehören zu dem Stück, in dem sie beginnen, mit ihrer Stelle darin.
                    java.util.List<PageTextIndex.Heading> headings = section.headings().stream()
                            .filter(heading -> prefix.length() + heading.start() >= offset
                                    && prefix.length() + heading.start() < offset + chunk.length())
                            .map(heading -> new PageTextIndex.Heading(heading.text(),
                                    prefix.length() + heading.start() - offset))
                            .toList();
                    return new PageTextIndex.Entry(snapshot.key(), metadata.partitionKey(), metadata.title(),
                            chunk, input, mediaType, nameChars, headings);
                });
    }
}
