package com.aresstack.spaceharvester.application.export;

import java.util.Objects;

/**
 * Eine Seite der Generation, die nicht exportiert werden konnte (etwa ohne Payload oder während des Exports geändert).
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
