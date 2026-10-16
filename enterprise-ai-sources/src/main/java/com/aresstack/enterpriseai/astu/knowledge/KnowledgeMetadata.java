package com.aresstack.enterpriseai.astu.knowledge;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Unveränderliche, sortierte Zusatzmetadaten einer Ressource als Text-Schlüssel/Wert-Paare (z. B.
 * {@code space=DEV}, {@code labels=howto,java}, {@code author=...}).
 *
 * <p>Metadaten gehen in Index und Prompt-Kontext ein. Sie dürfen deshalb niemals Secrets, Tokens oder
 * Zugangsdaten enthalten; Adapter legen hier nur fachliche, ohnehin sichtbare Angaben ab. Schlüssel sind nicht
 * leer und ohne Leerraum am Rand, Werte nicht {@code null}.
 */
public final class KnowledgeMetadata {

    /** Größe einer Datei-Ressource in Bytes (Dezimalzahl), wenn die Quelle sie kennt. */
    public static final String FILE_SIZE = "file.size";

    private final SortedMap<String, String> entries;

    private KnowledgeMetadata(SortedMap<String, String> entries) {
        this.entries = Collections.unmodifiableSortedMap(entries);
    }

    public static KnowledgeMetadata empty() {
        return new KnowledgeMetadata(new TreeMap<String, String>());
    }

    public static KnowledgeMetadata of(Map<String, String> entries) {
        TreeMap<String, String> copy = new TreeMap<String, String>();
        if (entries != null) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                put(copy, entry.getKey(), entry.getValue());
            }
        }
        return new KnowledgeMetadata(copy);
    }

    /** Liefert eine Kopie mit zusätzlichem bzw. ersetztem Eintrag. */
    public KnowledgeMetadata with(String key, String value) {
        TreeMap<String, String> copy = new TreeMap<String, String>(entries);
        put(copy, key, value);
        return new KnowledgeMetadata(copy);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public Set<String> keys() {
        return entries.keySet();
    }

    /** Unveränderliche, nach Schlüssel sortierte Sicht. */
    public SortedMap<String, String> asMap() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof KnowledgeMetadata && entries.equals(((KnowledgeMetadata) other).entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "KnowledgeMetadata" + entries;
    }

    private static void put(TreeMap<String, String> target, String key, String value) {
        if (key == null || key.isEmpty() || KnowledgeText.hasEdgeSpace(key)) {
            throw new IllegalArgumentException("Metadaten-Schlüssel darf nicht leer sein und keinen Leerraum am Rand haben: "
                    + SourceAddress.quote(key));
        }
        if (value == null) {
            throw new IllegalArgumentException("Metadaten-Wert für '" + key + "' darf nicht null sein");
        }
        target.put(key, value);
    }
}
