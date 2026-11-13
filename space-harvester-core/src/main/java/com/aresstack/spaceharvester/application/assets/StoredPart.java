package com.aresstack.spaceharvester.application.assets;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ein abgelegter Teil im Stand seines äußeren Behälters (ADR 0026, AP7b) ohne seine Blöcke: die liest {@link
 * ExtractionStore#partSections} abschnittsweise. Inhaltsgleiche Einträge desselben Behälters teilen sich einen Teil.
 *
 * @param key      Schlüssel der Ablage ({@link PartId#storageKey()}), nur {@code [0-9a-z-]}
 * @param head     Kopfdaten des Teils (etwa Betreff und Absender einer weitergeleiteten Mail)
 * @param sections Anzahl der Abschnitte
 * @param blocks   Anzahl der Blöcke
 * @param chars    Zeichen in allen Blöcken
 */
public record StoredPart(String key, DocumentHead head, int sections, long blocks, long chars) {

    private static final Pattern KEY = Pattern.compile("[0-9a-z-]{1,200}");

    public StoredPart {
        checkKey(key);
        Objects.requireNonNull(head, "head");
        if (sections < 0 || blocks < 0 || chars < 0) {
            throw new IllegalArgumentException("Zähler dürfen nicht negativ sein");
        }
    }

    /** @throws IllegalArgumentException wenn der Schlüssel nicht pfadsicher ist */
    public static String checkKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Teilschlüssel nur aus [0-9a-z-]");
        }
        return key;
    }
}
