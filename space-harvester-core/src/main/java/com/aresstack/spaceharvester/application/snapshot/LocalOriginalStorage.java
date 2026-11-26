package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.assets.DirectDocumentExtractions;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Keep metadata and a structured extraction reference, not the local original's bytes. */
public final class LocalOriginalStorage implements SnapshotStorageStrategy {

    private final DirectDocumentExtractions documents;
    private final Predicate<String> readable;

    public LocalOriginalStorage(DirectDocumentExtractions documents, Predicate<String> readable) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.readable = Objects.requireNonNull(readable, "readable");
    }

    @Override
    public boolean supportsSource(HarvestSource source) {
        return "files".equals(source.type()) && source.readsOriginalInPlace();
    }

    @Override
    public boolean supports(HarvestSource source, PageMetadata metadata) {
        return supportsSource(source)
                && PageMetadata.FILE_KIND.equals(metadata.attributes().get(PageMetadata.RESOURCE_KIND))
                && !"true".equals(metadata.attributes().get(PageMetadata.REMOTE_MEDIA));
    }

    @Override
    public PageSnapshot capture(HarvestSource source, PageMetadata metadata) {
        if (!supports(source, metadata)) {
            throw new IllegalArgumentException("Local original storage requires a direct local file");
        }
        Optional<DirectDocumentExtractions.Original> original = documents.extract(source, metadata, readable);
        if (original.isPresent()) {
            DirectDocumentExtractions.Original read = original.get();
            return PageSnapshot.of(source.id(), read.metadata(), LocalOriginalReference.of(read).payload(), Instant.now());
        }
        // Keep only verified metadata for a type not admitted for text extraction.
        PageMetadata current = source.pageMetadata(metadata.pageId());
        DirectDocumentExtractions.requireSameRevision(source, metadata, current);
        return PageSnapshot.of(source.id(), current, LocalOriginalReference.unsupported().payload(), Instant.now());
    }
}
