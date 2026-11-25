package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Project retained text for legacy page readers; section processors use the extraction directly. */
public final class LocalReferenceTextProjector implements PageTextProjector {

    private final DocumentExtractions documents;

    public LocalReferenceTextProjector(DocumentExtractions documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        return LocalOriginalReference.read(snapshot.payload())
                .flatMap(reference -> Optional.ofNullable(reference.extractionKey()))
                .flatMap(documents::stored)
                .map(documents::originalText)
                .filter(text -> !text.text().isBlank())
                .map(text -> new PageText("local-original-text/v1", text.text(), List.of()));
    }
}
