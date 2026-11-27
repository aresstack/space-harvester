package com.aresstack.knowledge4j.source;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The kind of a source. Extensible: the libraries ship a few common kinds, a host adds its own (a data record,
 * a transcript segment, a table cell, a mainframe object) without changing Knowledge4j.
 */
public record SourceKind(String id) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public static final SourceKind WEB_PAGE = new SourceKind("web-page");
    public static final SourceKind INTERNAL_DOCUMENT = new SourceKind("internal-document");
    public static final SourceKind CODE_LOCATION = new SourceKind("code-location");

    public SourceKind {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid source kind: " + id
                    + " (expected lower-case [a-z][a-z0-9._-]{0,63})");
        }
    }

    public static SourceKind of(String id) {
        return new SourceKind(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
