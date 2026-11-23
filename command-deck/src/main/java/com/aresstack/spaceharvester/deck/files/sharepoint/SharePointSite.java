package com.aresstack.spaceharvester.deck.files.sharepoint;

import java.util.Objects;
import java.util.Optional;

/** Eine SharePoint-Site oder Dokumentbibliothek mit Anzeigename und Web-Adresse (wie in MainframeMate). */
public record SharePointSite(String name, String url) {

    public SharePointSite {
        Objects.requireNonNull(url, "url");
        name = name == null || name.isBlank() ? SharePointPaths.displayName(url) : name.strip();
    }

    /** Der WebDAV-UNC-Pfad der Site. */
    public Optional<String> uncPath() {
        return SharePointPaths.toUncPath(url);
    }
}
