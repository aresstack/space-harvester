package com.aresstack.spaceharvester.application.configuration;

import java.nio.file.Path;

/**
 * Speicherort von Cache, Indizes und Aufträgen, unter {@code space-harvester.cache.directory}. <strong>Nicht zur
 * Laufzeit änderbar:</strong> Eine Änderung nimmt der {@link ConfigurationManager} an, meldet sie als
 * {@link ConfigurationManager.Activation#restartRequired()} und lässt den bisherigen Ort aktiv, bis das Programm
 * kontrolliert neu startet.
 *
 * @param dataDirectory Verzeichnis oder {@code null} für den Standard des Programms
 */
public record StorageSettings(Path dataDirectory) {

    public static final String DATA_DIRECTORY = "cache.directory";

    public static final StorageSettings DEFAULT = new StorageSettings(null);
}
