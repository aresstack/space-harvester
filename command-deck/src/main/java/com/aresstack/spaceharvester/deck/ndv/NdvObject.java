package com.aresstack.spaceharvester.deck.ndv;

import java.util.Objects;

/**
 * Ein Natural-Objekt in einer Bibliothek des NDV-Servers, so wie die Auflistung es liefert.
 *
 * @param library    Bibliothek, in Großbuchstaben
 * @param name       Objektname (bei DDMs der Kurzname, der leer sein kann)
 * @param type       Art des Objekts
 * @param source     ob es Quelltext hat (sonst nur übersetzt)
 * @param sourceSize Größe des Quelltexts in Bytes laut Server
 * @param user       wer zuletzt gespeichert hat
 * @param changed    Datum der letzten Speicherung, wie der Server es schreibt
 * @param location   wo das Objekt auf dem Server liegt; braucht der Adapter zum Lesen und Schreiben
 * @param details    weitere Angaben des Servers (Modus, Codepage, GP, Zugriff), wie MainframeMate sie zeigt
 */
public record NdvObject(String library, String name, NdvObjectType type, boolean source, int sourceSize,
                        String user, String changed, Location location, Details details) {

    public NdvObject(String library, String name, NdvObjectType type, boolean source, int sourceSize, String user,
                     String changed, Location location) {
        this(library, name, type, source, sourceSize, user, changed, location, Details.NONE);
    }

    public NdvObject {
        library = library == null ? "" : library.trim();
        name = name == null ? "" : name.trim();
        Objects.requireNonNull(type, "type");
        user = user == null ? "" : user.trim();
        changed = changed == null ? "" : changed.trim();
        Objects.requireNonNull(location, "location");
        details = details == null ? Details.NONE : details;
    }

    /** Der Name, unter dem das Objekt gezeigt und geladen wird: bei DDMs der lange Name, sonst der Name. */
    public String displayName() {
        return location.ddm() && !location.longName().isEmpty() ? location.longName() : name;
    }

    /** Der Pfad wie in MainframeMate, etwa {@code MYLIB/PROG1.NSP}. */
    public String path() {
        return library + "/" + displayName() + (type.extension().isEmpty() ? "" : "." + type.extension());
    }

    /**
     * Fundstelle auf dem Server (Adabas-Datenbank und -Datei), roh vom Adapter übernommen.
     *
     * @param databaseId Datenbanknummer (DBID), ≤ 0 wenn unbekannt
     * @param fileNumber Dateinummer (FNR), ≤ 0 wenn unbekannt
     * @param longName   langer Name (DDMs), sonst leer
     * @param kind       Kennzahl der Objektart beim Server (Quelle, übersetzt, beides)
     * @param ddm        ob es ein DDM ist (liegt global, nicht in der Bibliothek)
     */
    public record Location(int databaseId, int fileNumber, String longName, int kind, boolean ddm) {

        public Location {
            longName = longName == null ? "" : longName.trim();
        }
    }

    /**
     * Angaben, die MainframeMate in der Detailansicht, im Kopf des Editors und im Info-Fenster zeigt.
     *
     * @param mode       Programmiermodus: {@code Structured}, {@code Reporting} oder leer
     * @param kind       Objektart beim Server: {@code Source}, {@code GP}, {@code Source/GP} …
     * @param codePage   Codepage des Quelltexts, leer wenn unbekannt
     * @param gpUser     wer das übersetzte Objekt (GP) zuletzt erzeugt hat
     * @param gpDate     wann das GP erzeugt wurde
     * @param accessDate letzter Zugriff
     */
    public record Details(String mode, String kind, String codePage, String gpUser, String gpDate,
                          String accessDate) {

        public static final Details NONE = new Details("", "", "", "", "", "");

        public Details {
            mode = clean(mode);
            kind = clean(kind);
            codePage = clean(codePage);
            gpUser = clean(gpUser);
            gpDate = clean(gpDate);
            accessDate = clean(accessDate);
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
