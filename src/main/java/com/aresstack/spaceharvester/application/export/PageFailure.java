package com.aresstack.spaceharvester.application.export;

import java.util.Objects;

/**
 * Eine Seite, die der Leser kennt, aber nicht liefern kann (etwa fehlende oder beschädigte Payload).
 *
 * @param pageId  Seite
 * @param code    maschinenlesbarer Grund
 * @param message für Manifest und API bestimmt; enthält keine Zugangsdaten
 */
public record PageFailure(String pageId, String code, String message) {

    public PageFailure {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
