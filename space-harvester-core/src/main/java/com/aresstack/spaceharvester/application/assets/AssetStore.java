package com.aresstack.spaceharvester.application.assets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    /**
     * Der abgelegte Anhang als Strom, ohne ihn ganz in den Speicher zu laden. Der Hash wird beim Lesen geprüft; eine
     * Abweichung meldet der Strom am Ende als {@link IOException}, bevor er das Ende anzeigt.
     *
     * @throws AssetStoreException wenn der Inhalt fehlt oder nicht lesbar ist
     */
    default InputStream open(Asset asset) {
        return new ByteArrayInputStream(read(asset));
    }

    /** Die Beschreibung genau dieser Bildbytes durch genau diesen Beschreiber und dieses Modell. */
    Optional<ImageDescription> description(String imageSha256, String describer, String modelVersion);

    /** Legt eine Beschreibung ab; eine vorhandene für dasselbe Bild und Modell wird ersetzt. */
    void putDescription(ImageDescription description);

    /** Der Text genau dieser Bytes aus genau dieser Extraktion (#183). */
    Optional<AssetText> text(String sha256, String extractor, String version);

    /** Legt einen Text ab; ein vorhandener für dieselben Bytes und dieselbe Extraktion wird ersetzt. */
    void putText(AssetText text);

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

            @Override
            public Optional<ImageDescription> description(String imageSha256, String describer,
                                                          String modelVersion) {
                return Optional.empty();
            }

            @Override
            public void putDescription(ImageDescription description) {
                throw new AssetStoreException("Keine Asset-Ablage konfiguriert", null);
            }

            @Override
            public Optional<AssetText> text(String sha256, String extractor, String version) {
                return Optional.empty();
            }

            @Override
            public void putText(AssetText text) {
                throw new AssetStoreException("Keine Asset-Ablage konfiguriert", null);
            }
        };
    }
}
