package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.mainframe.calltree.SourceResolver;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Löst Aufrufziele (CALLNAT, CALL, EXEC PGM, DDM-Namen) zu Quelltext auf und öffnet sie, für Mindmap, ER-Diagramm und
 * Doppelklick im Diagramm. In MainframeMate kam der Quelltext aus dem NDV-Quellen-Cache (aktuelle Bibliothek, dann
 * Suchreihenfolge); hier zuerst aus den offenen Dokumenten, dann aus derselben Ablage neben dem Dokument (gleicher
 * Ordner, gleiche Bibliothek, gleiches PDS). Blockiert, nie auf dem EDT aufrufen.
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
        if (openSession(targetName).isPresent()) {
            return true;
        }
        for (String location : candidates(targetName)) {
            try {
                String name = origin.store().load(location).name();
                if (editors != null) {
                    editors.open(origin.store(), location, name);
                    return true;
                }
            } catch (DocumentException | RuntimeException e) {
                // nächster Kandidat
            }
        }
        return false;
    }

    private List<String> candidates(String targetName) {
        try {
            return origin.store().siblings(origin.location(), clean(targetName));
        } catch (DocumentException | RuntimeException e) {
            return List.of();
        }
    }

    private Optional<EditorSession> openSession(String targetName) {
        if (editors == null) {
            return Optional.empty();
        }
        String wanted = clean(targetName).toUpperCase(Locale.ROOT);
        return editors.all().stream()
                .filter(session -> session != origin && base(session.name()).equals(wanted))
                .findFirst();
    }

    /** Diagramm-Labels tragen oft Zusätze („CALLNAT PROG1“, Anführungszeichen, Zeilenumbrüche): nur der Name zählt. */
    static String clean(String label) {
        String text = label.replace("\\n", " ").replace('"', ' ').replace('\'', ' ').trim();
        String[] words = text.split("\\s+");
        return words.length == 0 ? "" : words[words.length - 1];
    }

    private static String base(String name) {
        String upper = name == null ? "" : name.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        return dot > 0 ? upper.substring(0, dot) : upper;
    }
}
