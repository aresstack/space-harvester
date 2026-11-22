package com.aresstack.spaceharvester.deck.ftp;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Ein Eintrag einer Liste auf dem Host: auf MVS ein Dataset (Ordner) oder ein Member (Datei), sonst Ordner und Dateien.
 *
 * @param name      Anzeigename (unter einem Dataset nur der nächste Teil)
 * @param path      absoluter Pfad zum Weiterarbeiten
 * @param directory ob man hineinwechseln kann
 * @param size      Größe in Bytes, -1 wenn unbekannt
 * @param modified  Änderungszeit, wenn der Host sie nennt
 */
public record RemoteEntry(String name, String path, boolean directory, long size, Optional<Instant> modified) {

    public RemoteEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        modified = modified == null ? Optional.empty() : modified;
    }

    public static RemoteEntry directory(String name, String path) {
        return new RemoteEntry(name, path, true, -1, Optional.empty());
    }

    public static RemoteEntry file(String name, String path) {
        return new RemoteEntry(name, path, false, -1, Optional.empty());
    }
}
