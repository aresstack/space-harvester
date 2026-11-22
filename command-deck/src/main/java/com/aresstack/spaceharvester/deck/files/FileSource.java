package com.aresstack.spaceharvester.deck.files;

import java.util.List;
import java.util.Optional;

/**
 * Port „Dateisystem“ des Arbeitsbereichs Dateien: lokale Dateien, später SharePoint und weitere. Funktionsumfang wie
 * der {@code FileService} von MainframeMate (auflisten, Ordner/Datei anlegen, löschen); Lesen und Schreiben von
 * Inhalten übernimmt der Editor-Port {@code deck.editor.DocumentStore}. {@link #normalize}, {@link #parent} und
 * {@link #child} rechnen nur mit Adressen und fragen das System nicht; alle anderen Aufrufe dürfen blockieren und
 * laufen nie auf dem EDT.
 */
public interface FileSource {

    /** Name der Quelle für die Anzeige, etwa „Lokale Dateien“. */
    String name();

    /** Wo der Arbeitsbereich ohne gemerkten Ort beginnt. */
    String home();

    /** Schnell erreichbare Orte (Laufwerke, Benutzerordner, freigegebene Ordner), in Anzeige-Reihenfolge. */
    List<Place> places();

    /** Macht aus einer eingegebenen Adresse die eindeutige Form der Quelle (absolut, ohne „..“). */
    String normalize(String location) throws FileAccessException;

    /** Der übergeordnete Ort, leer an der Wurzel. */
    Optional<String> parent(String location);

    /** Die Adresse des Eintrags {@code name} im Ordner {@code folder}. */
    String child(String folder, String name);

    /** Die Einträge eines Ordners in beliebiger Reihenfolge. */
    List<FileEntry> list(String folder) throws FileAccessException;

    /** Legt einen Ordner an; gibt es den Namen schon, scheitert der Aufruf. */
    void createFolder(String location) throws FileAccessException;

    /** Legt eine leere Datei an; gibt es den Namen schon, scheitert der Aufruf (nie überschreiben). */
    void createFile(String location) throws FileAccessException;

    /** Löscht eine Datei oder einen leeren Ordner (wie MainframeMate: kein rekursives Löschen). */
    void delete(String location) throws FileAccessException;

    /** Ein schnell erreichbarer Ort. */
    record Place(String label, String location) {
    }
}
