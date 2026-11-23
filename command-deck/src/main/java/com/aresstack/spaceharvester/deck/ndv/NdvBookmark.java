package com.aresstack.spaceharvester.deck.ndv;

import java.util.Locale;
import java.util.Optional;

/**
 * Lesezeichen wie in MainframeMate: eine Bibliothek oder ein Objekt darin ({@code ndv://LIB} bzw.
 * {@code ndv://LIB/NAME.EXT}). Beim Öffnen wird die Bibliothek geladen und das Objekt nach Namen gesucht.
 *
 * @param library Bibliothek in Großbuchstaben
 * @param object  Objektname mit Endung, leer für die Bibliothek
 */
public record NdvBookmark(String library, String object) {

    public NdvBookmark {
        library = library == null ? "" : library.trim().toUpperCase(Locale.ROOT);
        object = object == null ? "" : object.trim();
        if (library.isEmpty()) {
            throw new IllegalArgumentException("Bibliothek fehlt");
        }
    }

    public static NdvBookmark of(NdvObject object) {
        return new NdvBookmark(object.library(), object.path().substring(object.library().length() + 1));
    }

    /** {@code ndv://LIB} oder {@code ndv://LIB/NAME.EXT}. */
    public String path() {
        return "ndv://" + library + (object.isEmpty() ? "" : "/" + object);
    }

    /** Für die Anzeige: {@code LIB} oder {@code LIB/NAME.EXT}. */
    public String label() {
        return object.isEmpty() ? library : library + "/" + object;
    }

    /** Der Objektname ohne Endung, in Großbuchstaben, zum Suchen in der Bibliothek. */
    public String objectName() {
        int dot = object.lastIndexOf('.');
        return (dot > 0 ? object.substring(0, dot) : object).toUpperCase(Locale.ROOT);
    }

    public static Optional<NdvBookmark> parse(String path) {
        if (path == null) {
            return Optional.empty();
        }
        String rest = path.trim();
        if (rest.startsWith("ndv://")) {
            rest = rest.substring(6);
        }
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        int slash = rest.indexOf('/');
        return Optional.of(slash < 0 ? new NdvBookmark(rest, "")
                : new NdvBookmark(rest.substring(0, slash), rest.substring(slash + 1)));
    }
}
