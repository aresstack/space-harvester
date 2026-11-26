package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.app.ui.deck.NdvAnalysisSettings;
import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.ndv.NdvDocumentStore;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvLibraryResolver;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache;
import com.aresstack.spaceharvester.mainframe.calltree.SourceResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Löst Aufrufziele (CALLNAT, CALL, EXEC PGM, DDM-Namen) zu Quelltext auf und öffnet sie, für Mindmap, ER-Diagramm und
 * Doppelklick im Diagramm. Zuerst aus den offenen Dokumenten; bei einer NDV-Quelle wie in MainframeMate aus dem
 * NDV-Quellen-Cache: die Bibliothek bestimmt {@link NdvLibraryResolver} (eigene Bibliothek, Standard-Bibliothek, dann
 * die Suchreihenfolge aus Einstellungen › NDV; gewinnt die erste, deren Cache das Objekt hat), der Quelltext kommt aus
 * dem Cache, sonst vom Server aus dieser Bibliothek. Sonst aus derselben Ablage neben dem Dokument (gleicher Ordner,
 * gleiche Bibliothek, gleiches PDS). Blockiert, nie auf dem EDT aufrufen.
 */
final class CallTargets implements SourceResolver {

    private final EditorSessions editors;
    private final EditorSession origin;

    CallTargets(EditorSessions editors, EditorSession origin) {
        this.editors = editors;
        this.origin = origin;
    }

    @Override
    public String resolve(String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return null;
        }
        Optional<EditorSession> open = openSession(targetName);
        if (open.isPresent() && open.get().text().isPresent()) {
            return open.get().text().get();
        }
        Optional<String> cached = ndv().flatMap(ndv -> ndv.source(clean(targetName)));
        if (cached.isPresent()) {
            return cached.get();
        }
        for (String location : candidates(targetName)) {
            try {
                return origin.store().load(location).text();
            } catch (DocumentException | RuntimeException e) {
                // nächster Kandidat
            }
        }
        return null;
    }

    /** Öffnet das Ziel im Editor seines Bereichs; {@code false}, wenn es nicht zu finden war. */
    boolean open(String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return false;
        }
        Optional<EditorSession> open = openSession(targetName);
        if (open.isPresent()) {
            editors.reveal(open.get());
            return true;
        }
        for (String location : candidates(targetName)) {
            try {
                String name = origin.store().load(location).name();
                if (editors != null) {
                    editors.reveal(editors.open(origin.store(), location, name));
                    return true;
                }
            } catch (DocumentException | RuntimeException e) {
                // nächster Kandidat
            }
        }
        return false;
    }

    private List<String> candidates(String targetName) {
        String name = clean(targetName);
        List<String> found = new ArrayList<>();
        // NDV: zuerst in der Bibliothek, die Suchreihenfolge und Cache nennen (wie openByName im NDV-Bereich)
        ndv().flatMap(ndv -> ndv.library(name)).ifPresent(library -> found.addAll(siblings(library + "/" + name, name)));
        for (String location : siblings(origin.location(), name)) {
            if (!found.contains(location)) {
                found.add(location);
            }
        }
        return found;
    }

    private List<String> siblings(String location, String name) {
        try {
            return origin.store().siblings(location, name);
        } catch (DocumentException | RuntimeException e) {
            return List.of();
        }
    }

    /** Der NDV-Quellen-Cache des Servers, wenn das Dokument aus NDV stammt. */
    private Optional<NdvLookup> ndv() {
        if (origin == null || !(origin.store() instanceof NdvDocumentStore store)) {
            return Optional.empty();
        }
        try {
            var profile = store.session().profile();
            return Optional.of(new NdvLookup(NdvAnalysisSettings.resolver(),
                    NdvAnalysisSettings.analysis(store.session()).cache(), library(origin.location()),
                    profile.defaultLibrary()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String library(String location) {
        return location != null && location.contains("/") ? location.substring(0, location.indexOf('/')) : "";
    }

    /**
     * Nachschlagen im NDV-Quellen-Cache wie MainframeMate: Bibliothek über Suchreihenfolge und Cache, Quelltext aus
     * dem Cache dieser Bibliothek.
     */
    record NdvLookup(NdvLibraryResolver resolver, NdvSourceCache cache, String currentLibrary, String defaultLibrary) {

        Optional<String> library(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            return resolver.resolve(name.toUpperCase(Locale.ROOT), currentLibrary, defaultLibrary, cache);
        }

        Optional<String> source(String name) {
            return library(name).flatMap(library -> cache.source(library, name.toUpperCase(Locale.ROOT)));
        }
    }

    private Optional<EditorSession> openSession(String targetName) {
        if (editors == null) {
            return Optional.empty();
        }
        String wanted = clean(targetName).toUpperCase(Locale.ROOT);
        if (origin != null && origin.store() instanceof NdvDocumentStore ndvOrigin) {
            List<EditorSession> matching = editors.all().stream()
                    .filter(session -> session != origin && base(session.name()).equals(wanted))
                    .filter(session -> session.store() instanceof NdvDocumentStore store
                            && store.session() == ndvOrigin.session()).toList();
            return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
        }
        return editors.all().stream()
                .filter(session -> session != origin && base(session.name()).equals(wanted))
                .findFirst();
    }

    /** Wörter, die in Knoten-Beschriftungen vor oder nach dem Namen stehen, aber selbst kein Ziel sind. */
    private static final java.util.Set<String> KEYWORDS = java.util.Set.of("CALLNAT", "CALL", "FETCH", "RETURN",
            "PERFORM", "EXEC", "LINK", "RUN", "USING", "INCLUDE", "COPY", "PROC", "PGM", "DDM", "VIEW", "OF", "STEP");
    private static final java.util.regex.Pattern PGM = java.util.regex.Pattern.compile(
            "PGM=([A-Za-z0-9$#@_-]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Der Name des Ziels aus der Knoten-Beschriftung des Konverters: „CALLNAT PROG1“, „SUBPROG\n(CALLNAT)“,
     * „STEP1\nPGM=IEFBR14“ (dann das Programm), Anführungszeichen, Symbole. Genommen wird das letzte Wort der ersten
     * Zeile, die nach Abzug von Schlüsselwörtern und Klammerzusätzen noch eines hat.
     */
    static String clean(String label) {
        if (label == null) {
            return "";
        }
        String text = label.replace("\\n", "\n").replace("<br/>", "\n").replace("<br>", "\n");
        java.util.regex.Matcher pgm = PGM.matcher(text);
        if (pgm.find()) {
            return pgm.group(1);
        }
        for (String line : text.split("\\r?\\n")) {
            String found = null;
            for (String word : line.replaceAll("\\([^)]*\\)", " ").split("[\\s\"']+")) {
                String name = word.replaceAll("[^A-Za-z0-9$#@_.-]", "");
                if (!name.isEmpty() && !KEYWORDS.contains(name.toUpperCase(Locale.ROOT))) {
                    found = name;
                }
            }
            if (found != null) {
                return found;
            }
        }
        return "";
    }

    private static String base(String name) {
        String upper = name == null ? "" : name.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        return dot > 0 ? upper.substring(0, dot) : upper;
    }
}
