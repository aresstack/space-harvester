package com.aresstack.spaceharvester.application.sources;

import java.util.Objects;

/**
 * Ein fachlicher Bereich einer Quelle, bei Confluence ein Space.
 *
 * @param key            Schlüssel der Partition, z. B. der Space-Key {@code DEV}
 * @param name           Anzeigename
 * @param homeResourceId ID der Startressource (bei Confluence die Startseite) oder {@code null}, wenn unbekannt
 */
public record Partition(String key, String name, String homeResourceId) {

    public Partition {
        Objects.requireNonNull(key, "key");
        name = name == null ? key : name;
    }
}
