package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Optional;

/** Supply the bounded compatibility preview to page-level consumers. Section analyses use the extraction store. */
public final class LocalReferenceTextProjector implements PageTextProjector {

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        return LocalOriginalReference.read(snapshot.payload()).filter(reference -> !reference.preview().isBlank())
                .map(reference -> new PageText("local-original-preview/v1", reference.preview(), List.of()));
    }
}
