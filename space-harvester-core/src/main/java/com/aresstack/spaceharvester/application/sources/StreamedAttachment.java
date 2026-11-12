package com.aresstack.spaceharvester.application.sources;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein Anhang, dessen Bytes als Strom in ein {@link AttachmentSink} geflossen sind: nur seine Metadaten, wie die Quelle
 * sie beim Download nannte.
 *
 * @param attachment  Metadaten beim Download; die Version kann neuer sein als in einer vorher gelesenen Liste
 * @param retrievedAt wann die Quelle geantwortet hat
 */
public record StreamedAttachment(PageAttachment attachment, Instant retrievedAt) {

    public StreamedAttachment {
        Objects.requireNonNull(attachment, "attachment");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }
}
