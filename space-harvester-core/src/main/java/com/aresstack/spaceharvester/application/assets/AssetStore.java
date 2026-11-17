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

    /** Eine leere Datei für einen Download, der danach mit {@link #putFile} abgelegt wird (Streaming, ohne Grenze). */
    default java.nio.file.Path stagingFile() throws java.io.IOException {
        return java.nio.file.Files.createTempFile("asset-", ".part");
    }

    /**
     * Legt den Inhalt aus {@code file} ab; Hash und Größe müssen passen wie bei {@link #put}. Die Datei gehört danach
     * der Ablage (verschoben oder gelöscht). Der Standard liest sie in den Speicher.
     */
    default void putFile(Asset asset, java.nio.file.Path file) {
        try {
            put(asset, java.nio.file.Files.readAllBytes(file));
        } catch (java.io.IOException e) {
            throw new AssetStoreException("Asset nicht ablegbar", e);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(file);
            } catch (java.io.IOException e) {
                // nichts weiter: eine temporäre Datei
            }
        }
    }

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

    /**
     * Die abgelegte Datei eines Anhangs zum Lesen, ohne Kopie und ohne die Bytes in den Speicher zu laden (ADR 0026:
     * Extraktion aus der Datei). Der Hash wird dabei nicht geprüft; die Datei darf nicht verändert werden.
     *
     * @return leer, wenn die Ablage keine Dateien hat oder der Inhalt fehlt; dann bleibt {@link #open}
     */
    default Optional<java.nio.file.Path> file(Asset asset) {
        return Optional.empty();
    }

    /** Die Beschreibung genau dieser Bildbytes durch genau diesen Beschreiber und dieses Modell. */
    Optional<ImageDescription> description(String imageSha256, String describer, String modelVersion);

    /** Legt eine Beschreibung ab; eine vorhandene für dasselbe Bild und Modell wird ersetzt. */
    void putDescription(ImageDescription description);

    /**
     * Entfernt alle Beschreibungen dieser Bildbytes, gleich welcher Beschreiber und welches Modell (ADR 0028: die
     * Freigabe für den KI-Dienst ist zurückgenommen). Die Bytes und ihre Texte bleiben. Der Standard entfernt nichts.
     */
    default void removeDescriptions(String imageSha256) {
    }

    /** Der Text genau dieser Bytes aus genau dieser Extraktion (#183). */
    Optional<AssetText> text(String sha256, String extractor, String version);

    /** Legt einen Text ab; ein vorhandener für dieselben Bytes und dieselbe Extraktion wird ersetzt. */
    void putText(AssetText text);

    /**
     * Der jüngste abgelegte Text dieser Bytes gleich welcher Extraktion und Version (ADR 0026, Punkt 11, Abnahme 8):
     * Bis ein Neulesen erfolgreich ist, bleibt er als veralteter Stand lieferbar. Leer, wenn keiner abgelegt ist; der
     * Standard kennt keine anderen Stände.
     */
    default Optional<AssetText> latestText(String sha256) {
        return Optional.empty();
    }

    /**
     * Entfernt die Texte dieser Bytes aus allen anderen Extraktionen und Versionen, nachdem das Original erfolgreich neu
     * gelesen ist (ADR 0026, Punkt 11); der Text von {@code extractor}/{@code version} bleibt. Der Standard entfernt
     * nichts.
     */
    default void removeOtherTexts(String sha256, String extractor, String version) {
    }

    /**
     * Alle abgelegten Einträge einer Quelle in allen Versionen, ohne Inhalte zu lesen („Neu lesen“, ADR 0026,
     * Punkt 11); beschädigte Einträge fehlen. Der Strom muss geschlossen werden. Der Standard kennt keine.
     */
    default java.util.stream.Stream<Asset> entries(String sourceId) {
        return java.util.stream.Stream.empty();
    }

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
