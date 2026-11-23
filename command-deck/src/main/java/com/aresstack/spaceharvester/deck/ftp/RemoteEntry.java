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
 * @param details   was {@code LIST} auf MVS zusätzlich nennt (Satzformat, Satzlänge …), leer ohne Details
 */
public record RemoteEntry(String name, String path, boolean directory, long size, Optional<Instant> modified,
                          Details details) {

    /**
     * Angaben aus {@code LIST} auf MVS, wie sie MainframeMate in der Detailansicht zeigt.
     *
     * @param recordFormat Recfm, etwa FB
     * @param recordLength Lrecl, 0 wenn unbekannt
     * @param blockSize    BlkSz, 0 wenn unbekannt
     * @param organization Dsorg, etwa PO oder PS
     * @param volume       Datenträger
     * @param lines        Zeilen eines Members (ISPF-Statistik), 0 wenn unbekannt
     * @param changedBy    wer ein Member zuletzt geändert hat (ISPF-Statistik)
     */
    public record Details(String recordFormat, int recordLength, int blockSize, String organization, String volume,
                          int lines, String changedBy) {

        public static final Details NONE = new Details("", 0, 0, "", "", 0, "");

        public Details {
            recordFormat = recordFormat == null ? "" : recordFormat.trim();
            organization = organization == null ? "" : organization.trim();
            volume = volume == null ? "" : volume.trim();
            changedBy = changedBy == null ? "" : changedBy.trim();
        }

        /** „FB/80“, leer wenn unbekannt. */
        public String format() {
            if (recordFormat.isEmpty()) {
                return "";
            }
            return recordLength > 0 ? recordFormat + "/" + recordLength : recordFormat;
        }
    }

    public RemoteEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        modified = modified == null ? Optional.empty() : modified;
        details = details == null ? Details.NONE : details;
    }

    public RemoteEntry(String name, String path, boolean directory, long size, Optional<Instant> modified) {
        this(name, path, directory, size, modified, Details.NONE);
    }

    public static RemoteEntry directory(String name, String path) {
        return new RemoteEntry(name, path, true, -1, Optional.empty());
    }

    public static RemoteEntry file(String name, String path) {
        return new RemoteEntry(name, path, false, -1, Optional.empty());
    }

    public RemoteEntry withDetails(Details value, long newSize, Optional<Instant> newModified) {
        return new RemoteEntry(name, path, directory, newSize >= 0 ? newSize : size,
                newModified.isPresent() ? newModified : modified, value);
    }

    /** Endung für „Nach Endung gruppieren“: Teil nach dem letzten Punkt, leer ohne. */
    public String extension() {
        int dot = name.lastIndexOf('.');
        return directory || dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(
                java.util.Locale.ROOT);
    }
}
