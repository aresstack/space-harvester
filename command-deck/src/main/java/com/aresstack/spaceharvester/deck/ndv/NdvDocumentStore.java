package com.aresstack.spaceharvester.deck.ndv;

import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.DocumentHash;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.SaveResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ablage des gemeinsamen Editors für Natural-Quellen einer NDV-Sitzung. Adresse ist der Pfad wie in MainframeMate
 * ({@code LIB/NAME.NSP}); welches Objekt dahinter steht, meldet der NDV-Bereich mit {@link #register(NdvObject)}.
 *
 * <p>Speichern wie MainframeMates {@code writeIfUnchanged}: Vorher wird der Quelltext neu vom Server gelesen; hat ihn
 * jemand seit dem Laden geändert, wird nicht gespeichert, sondern ein Konflikt gemeldet. Gelesen und geschrieben wird
 * nur auf ausdrücklichen Wunsch, nie beim Öffnen des Bereichs.
 */
public final class NdvDocumentStore implements DocumentStore {

    private final NdvSession session;
    private final Map<String, NdvObject> objects = new ConcurrentHashMap<>();

    public NdvDocumentStore(NdvSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public NdvSession session() {
        return session;
    }

    /** Merkt sich das Objekt hinter seinem Pfad und gibt den Pfad zurück, unter dem der Editor es öffnet. */
    public String register(NdvObject object) {
        objects.put(object.path(), object);
        return object.path();
    }

    /** Der Name für den Reiter des Editors: Objekt und Endung. */
    public static String name(NdvObject object) {
        String extension = object.type().extension();
        return object.displayName() + (extension.isEmpty() ? "" : "." + extension);
    }

    @Override
    public EditorDocument load(String location) throws DocumentException {
        NdvObject object = object(location);
        String text = read(object, "Laden");
        return document(location, object, text);
    }

    /** Objekte mit Quelltext und diesem Namen in der Bibliothek von {@code location} (Pfad {@code LIB/NAME.EXT}). */
    @Override
    public java.util.List<String> siblings(String location, String name) throws DocumentException {
        if (location == null || name == null || name.isBlank() || !location.contains("/")) {
            return java.util.List.of();
        }
        String library = location.substring(0, location.indexOf('/'));
        String wanted = name.trim();
        java.util.List<String> found = new java.util.ArrayList<>();
        try {
            session.objects(library, wanted, (page, total) -> {
                for (NdvObject object : page) {
                    if (object.source() && object.displayName().equalsIgnoreCase(wanted)) {
                        found.add(register(object));
                    }
                }
                return true;
            });
        } catch (IOException e) {
            throw new DocumentException("Suche in " + library + " gescheitert: " + NdvSessions.describe(e), e);
        }
        return found;
    }

    @Override
    public SaveResult save(EditorDocument loaded, String text, boolean overwrite) throws DocumentException {
        NdvObject object = object(loaded.location());
        if (!overwrite) {
            String current = read(object, "Prüfen vor dem Speichern");
            if (!hash(current).equals(loaded.hash())) {
                return new SaveResult.Conflict("„" + name(object) + "“ wurde auf dem Server seit dem Laden geändert.");
            }
        }
        try {
            session.write(object, text);
        } catch (IOException e) {
            throw new DocumentException(NdvSessions.describe(e), e);
        }
        return new SaveResult.Saved(document(loaded.location(), object, text));
    }

    private NdvObject object(String location) throws DocumentException {
        NdvObject object = objects.get(location);
        if (object == null) {
            throw new DocumentException("Unbekanntes NDV-Objekt: " + location);
        }
        return object;
    }

    private String read(NdvObject object, String step) throws DocumentException {
        try {
            String text = session.read(object);
            return text == null ? "" : text;
        } catch (IOException e) {
            throw new DocumentException(step + " gescheitert: " + NdvSessions.describe(e), e);
        }
    }

    private static EditorDocument document(String location, NdvObject object, String text) {
        // Der Server liefert Zeilen; NdvClient fügt sie mit \n zusammen und trennt sie beim Speichern wieder.
        return new EditorDocument(location, name(object), text, StandardCharsets.UTF_8.name(), "\n", hash(text));
    }

    private static String hash(String text) {
        return DocumentHash.of(text.getBytes(StandardCharsets.UTF_8));
    }
}
