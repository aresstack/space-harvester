package com.aresstack.spaceharvester.application.sources;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein heruntergeladener Anhang: seine Metadaten, wie die Quelle sie beim Download nannte, und die Originalbytes. Die
 * Bytes sind so schutzbedürftig wie die Seite und erscheinen nie in {@link #toString()}.
 *
 * @param attachment  Metadaten beim Download; die Version kann neuer sein als in einer vorher gelesenen Liste
 * @param content     Originalbytes, unverändert
 * @param retrievedAt wann die Quelle geantwortet hat
 */
public record AttachmentFile(PageAttachment attachment, byte[] content, Instant retrievedAt) {

    public AttachmentFile {
        Objects.requireNonNull(attachment, "attachment");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }

    @Override
    public String toString() {
        return "AttachmentFile[attachmentId=" + attachment.attachmentId() + ", bytes=" + content.length + "]";
    }
}
