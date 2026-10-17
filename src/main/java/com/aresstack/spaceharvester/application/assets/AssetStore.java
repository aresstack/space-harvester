package com.aresstack.spaceharvester.application.assets;

import java.util.Optional;

/**
 * Ablage geernteter Anhänge (#19): Inhalte nach {@code sha256} (je Inhalt einmal), dazu je Quelle, Anhang und Version
 * ein Eintrag. Ohne Version gibt es keinen Eintrag, weil sich dann nicht feststellen lässt, ob er noch aktuell ist.
 */
public interface AssetStore {

    /** Der abgelegte Anhang in genau dieser Version; leer ohne Version oder wenn er nicht abgelegt ist. */
    Optional<Asset> find(String sourceId, String attachmentId, String version);

    /**
     * Legt Inhalt und Eintrag ab; ein vorhandener Eintrag derselben Version wird ersetzt.
     *
     * @throws IllegalArgumentException wenn {@code sha256} oder {@code size} nicht zum Inhalt passen
     * @throws AssetStoreException      wenn die Ablage nicht schreiben kann
     */
    void put(Asset asset, byte[] content);

    /**
     * Die Bytes eines abgelegten Anhangs, geprüft gegen {@link Asset#sha256()}.
     *
     * @throws AssetStoreException wenn der Inhalt fehlt, nicht lesbar oder verändert ist
     */
    byte[] read(Asset asset);

    /** Ablage für einen Dienst ohne Asset-Verzeichnis: findet nichts, jedes Schreiben scheitert. */
    static AssetStore unavailable() {
        return new AssetStore() {
            @Override
            public Optional<Asset> find(String sourceId, String attachmentId, String version) {
                return Optional.empty();
            }

            @Override
            public void put(Asset asset, byte[] content) {
                throw new AssetStoreException("Keine Asset-Ablage konfiguriert", null);
            }

            @Override
            public byte[] read(Asset asset) {
                throw new AssetStoreException("Keine Asset-Ablage konfiguriert", null);
            }
        };
    }
}
