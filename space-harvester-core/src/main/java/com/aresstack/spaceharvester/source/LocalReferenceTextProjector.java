package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.enrichment.LocalReadingText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Project the same section text used by the stored local analyses. */
public final class LocalReferenceTextProjector implements PageTextProjector {

    private final LocalReadingText reading;

    public LocalReferenceTextProjector(DocumentExtractions documents) {
        this(new LocalReadingText(documents));
    }

    public LocalReferenceTextProjector(LocalReadingText reading) {
        this.reading = Objects.requireNonNull(reading, "reading");
    }

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        return reading.read(snapshot).map(LocalReadingText.Reading::text);
    }
}
