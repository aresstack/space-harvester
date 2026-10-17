package com.aresstack.spaceharvester.application.convert;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Erzeugt aus den Seiten eines Exports eine abgeleitete Datei. Ein Converter kennt weder Confluence noch den Cache: er
 * bekommt die nativen Snapshots und den Baum über {@link ConversionInput} und schreibt genau eine Datei.
 *
 * <p>Gleicher Input und gleiche {@link #version()} müssen inhaltlich dieselbe Datei ergeben; das Manifest hält beides
 * fest, damit sich ein Ergebnis nachvollziehen und erneut erzeugen lässt.
 */
public interface ExportConverter {

    /** Kennung im Parameter {@code format} des Exports, etwa {@code json} oder {@code markdown}. */
    String format();

    /** Version der Implementierung; jede Änderung am erzeugten Inhalt erhöht sie. */
    String version();

    /** Medientyp der erzeugten Datei. */
    String mediaType();

    /** Dateiname im Artefakt, etwa {@code pages.json}. */
    String fileName();

    /**
     * Schreibt die abgeleitete Datei. Der Strom wird vom Aufrufer geschlossen und kann mit
     * {@link ConversionLimitExceededException} abbrechen, wenn die konfigurierte Größe überschritten wird.
     */
    void convert(ConversionInput input, OutputStream out) throws IOException;
}
