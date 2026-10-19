package com.aresstack.spaceharvester.application.sources;

import java.util.Objects;

/**
 * Eine Änderung, die die Quelle selbst gemeldet hat (Confluence-Webhook, ADR 0014). Sie ist nur ein Hinweis: Wer sie
 * verarbeitet, fragt die Seite einzeln bei der Quelle nach und legt danach nur Kopfdaten in den Katalog.
 *
 * @param kind     was die Quelle meldet
 * @param pageId   ID der Seite
 * @param spaceKey Space laut Meldung oder {@code null}, wenn sie keinen nennt
 * @param version  Version laut Meldung oder {@code null}
 */
public record ChangeEvent(Kind kind, String pageId, String spaceKey, String version) {

    public enum Kind {
        /** Angelegt, geändert, verschoben oder wiederhergestellt. */
        CHANGED,
        /** In den Papierkorb gelegt oder endgültig gelöscht. */
        REMOVED,
        /** Die Berechtigungen der Seite haben sich geändert. */
        PERMISSIONS
    }

    public ChangeEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(pageId, "pageId");
        if (pageId.isBlank()) {
            throw new IllegalArgumentException("pageId fehlt");
        }
    }
}
