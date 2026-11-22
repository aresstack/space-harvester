package com.aresstack.spaceharvester.deck.files;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Ein Eintrag eines Verzeichnisses, unabhängig vom Dateisystem (lokal, später SharePoint und weitere). {@code location}
 * ist die Adresse, unter der die Quelle den Eintrag wiederfindet; die Oberfläche zeigt sie nur an.
 *
 * @param name      Anzeigename (letzter Pfadteil)
 * @param location  vollständige Adresse in der Quelle
 * @param directory ob es ein Ordner ist
 * @param size      Größe in Byte (bei Ordnern 0)
 * @param modified  letzte Änderung, {@code null} wenn unbekannt
 */
public record FileEntry(String name, String location, boolean directory, long size, Instant modified) {

    public FileEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
        size = Math.max(0L, size);
    }

    /** Die Dateiendung ohne Punkt in Kleinbuchstaben, bei Ordnern und Dateien ohne Endung leer. */
    public String extension() {
        if (directory) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
