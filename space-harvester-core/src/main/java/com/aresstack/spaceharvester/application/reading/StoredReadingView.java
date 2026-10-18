package com.aresstack.spaceharvester.application.reading;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Abgelegte Lese-Ansicht einer Seite, neben dem Raw-Snapshot und an ihn gebunden.
 *
 * @param key        Seite
 * @param rawSha256  {@code NativePayload#rawSha256()} des Snapshots, zu dem die Ansicht gehört
 * @param version    Version der Seite
 * @param renderedAt wann die Quelle die Ansicht geliefert hat
 */
public record StoredReadingView(PageKey key, String rawSha256, String version, String html, String baseUri,
                                String text, List<HarvestSource.ViewImage> images, Instant renderedAt) {

    public StoredReadingView {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(rawSha256, "rawSha256");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(renderedAt, "renderedAt");
        html = html == null ? "" : html;
        baseUri = baseUri == null ? "" : baseUri;
        text = text == null ? "" : text;
        images = List.copyOf(images);
    }
}
