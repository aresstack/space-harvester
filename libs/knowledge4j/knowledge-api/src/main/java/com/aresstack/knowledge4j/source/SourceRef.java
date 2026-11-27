package com.aresstack.knowledge4j.source;

import java.util.Objects;

/**
 * Proof of origin of a piece of content: which source, in which revision, where inside it. A {@code SourceRef}
 * is NOT a security boundary; whether content may leave the host is decided by the
 * {@link com.aresstack.knowledge4j.policy.TransferPolicy} from the content's {@link Provenance}.
 *
 * @param kind     the kind of source; extensible by the host
 * @param id       stable identifier of the source within its kind (a URL for a web page, a document id, a
 *                 program name with its library); never blank
 * @param revision revision, version or checksum of the source as read; empty when the source has none
 * @param location where inside the source; {@link SourceLocation#WHOLE} for the whole source
 * @param title    human-readable title for display; may be empty
 */
public record SourceRef(SourceKind kind, String id, String revision, SourceLocation location, String title) {

    public SourceRef {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a source reference needs an id");
        }
        revision = revision == null ? "" : revision;
        location = location == null ? SourceLocation.WHOLE : location;
        title = title == null ? "" : title;
    }

    public static SourceRef of(SourceKind kind, String id) {
        return new SourceRef(kind, id, "", SourceLocation.WHOLE, "");
    }

    public SourceRef at(SourceLocation newLocation) {
        return new SourceRef(kind, id, revision, newLocation, title);
    }

    public SourceRef withRevision(String newRevision) {
        return new SourceRef(kind, id, newRevision, location, title);
    }

    public SourceRef withTitle(String newTitle) {
        return new SourceRef(kind, id, revision, location, newTitle);
    }

    /** The same source regardless of location and revision. */
    public boolean sameSource(SourceRef other) {
        return other != null && kind.equals(other.kind) && id.equals(other.id);
    }
}
