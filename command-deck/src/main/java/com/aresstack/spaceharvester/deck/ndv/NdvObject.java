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
 */
public record NdvObject(String library, String name, NdvObjectType type, boolean source, int sourceSize,
                        String user, String changed, Location location) {

    public NdvObject {
        library = library == null ? "" : library.trim();
        name = name == null ? "" : name.trim();
        Objects.requireNonNull(type, "type");
        user = user == null ? "" : user.trim();
        changed = changed == null ? "" : changed.trim();
        Objects.requireNonNull(location, "location");
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
}
