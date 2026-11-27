package com.aresstack.spaceharvester.app.chat.artifact;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Artefakt eines Chats (ADR 0045, Abschnitt 1): feste Kennung, Art, Titel und eine Folge von Revisionen. Jede
 * Änderung, ob von der KI oder vom Benutzer, hängt eine Revision an; nichts wird überschrieben. „Rückgängig“ ist selbst
 * eine Revision, die den Stand vor der aktuellen wiederherstellt und deren Vorgänger übernimmt, sodass wiederholtes
 * Rückgängig Schritt für Schritt zurückgeht.
 */
public record ChatArtifact(String id, Kind kind, String title, long createdAt, List<Revision> revisions) {

    /** Dokument (Markdown in Abschnitten) oder Grafik (SVG). */
    public enum Kind { DOCUMENT, GRAPHIC }

    /** Wer eine Revision geschrieben hat. */
    public enum Origin { AI, USER }

    /**
     * Ein Stand des Artefakts.
     *
     * @param number  fortlaufend ab 1
     * @param parent  der Stand, auf den „Rückgängig“ zurückführt; 0 für den ersten
     * @param changed die Kennungen der berührten Abschnitte (für die Hervorhebung)
     * @param document die Abschnitte (nur Dokument)
     * @param svg      das SVG (nur Grafik)
     */
    public record Revision(long number, long parent, long at, Origin origin, String note, List<String> changed,
                           SectionDocument document, String svg) {
        public Revision {
            changed = changed == null ? List.of() : List.copyOf(changed);
            note = note == null ? "" : note;
            svg = svg == null ? "" : svg;
            document = document == null ? SectionDocument.empty() : document;
        }
    }

    public ChatArtifact {
        revisions = List.copyOf(revisions);
        title = title == null || title.isBlank() ? id : title.strip();
    }

    public Revision current() {
        return revisions.get(revisions.size() - 1);
    }

    public long revision() {
        return current().number();
    }

    public SectionDocument document() {
        return current().document();
    }

    /** Der Inhalt zum Anzeigen und Exportieren: Markdown des Dokuments oder das SVG der Grafik. */
    public String content() {
        return kind == Kind.GRAPHIC ? current().svg() : document().markdown();
    }

    /** Ob „Rückgängig“ etwas zurückholen kann. */
    public boolean undoable() {
        return current().parent() > 0;
    }

    public Revision revision(long number) {
        for (Revision revision : revisions) {
            if (revision.number() == number) {
                return revision;
            }
        }
        return null;
    }

    ChatArtifact with(Revision revision) {
        List<Revision> list = new ArrayList<>(revisions);
        list.add(revision);
        return new ChatArtifact(id, kind, title, createdAt, list);
    }

    ChatArtifact withTitle(String newTitle) {
        return new ChatArtifact(id, kind, newTitle, createdAt, revisions);
    }
}
