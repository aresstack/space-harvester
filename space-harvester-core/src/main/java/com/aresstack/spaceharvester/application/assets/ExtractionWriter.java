package com.aresstack.spaceharvester.application.assets;

/**
 * Schreibt eine Extraktion fortlaufend in Abschnitten (ADR 0026, Punkt 8). Blöcke kommen über {@link #accept} in
 * lückenloser Lesereihenfolge ab 0 und gehen sofort in die Ablage; ein Abschnitt wird geschlossen, bevor der nächste
 * Block seine Höchstgröße überschreiten würde. Ein Block über der Höchstgröße bildet einen Abschnitt allein.
 *
 * <p>Sichtbar wird die neue Extraktion erst mit {@link #commit}; bis dahin gilt die bisher abgelegte unverändert (wie ⟲
 * in ADR 0025). {@link #close()} ohne {@code commit} verwirft das Geschriebene.
 */
public interface ExtractionWriter extends BlockSink {

    /**
     * Macht die geschriebene Extraktion sichtbar und ersetzt eine vorhandene mit demselben Schlüssel.
     *
     * @param document Kopfdaten, Status und Verzeichnis; seine Zähler müssen zu den geschriebenen Blöcken passen
     * @throws IllegalArgumentException wenn die Zähler nicht passen
     * @throws AssetStoreException      wenn die Ablage nicht schreiben kann
     */
    StoredExtraction commit(StructuredDocument document);

    /** Verwirft das Geschriebene, wenn noch nicht bestätigt. */
    @Override
    void close();
}
