package com.aresstack.spaceharvester.app.chat.artifact;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Artefakt eines Projekts (ADR 0045 Abschnitt 1, ADR 0047): feste Kennung, Art, Titel, der Chat, der es erzeugt
 * hat ({@code chatId}, sonst {@code null}), und eine Folge von Revisionen. Jede
 * Änderung, ob von der KI oder vom Benutzer, hängt eine Revision an; nichts wird überschrieben. „Rückgängig“ ist selbst
 * eine Revision, die den Stand vor der aktuellen wiederherstellt und deren Vorgänger übernimmt, sodass wiederholtes
 * Rückgängig Schritt für Schritt zurückgeht.
 */
public record ChatArtifact(String id, Kind kind, String title, long createdAt, List<Revision> revisions,
                           String chatId) {

    /** Ohne erzeugenden Chat (ältere Ablage, Buch, Tests). */
    public ChatArtifact(String id, Kind kind, String title, long createdAt, List<Revision> revisions) {
        this(id, kind, title, createdAt, revisions, null);
    }

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
     * @param edition  die Auflage, die dieser Stand abschließt (Buch, ADR 0047), sonst 0
     */
    public record Revision(long number, long parent, long at, Origin origin, String note, List<String> changed,
                           SectionDocument document, String svg, int edition) {
        public Revision {
            changed = changed == null ? List.of() : List.copyOf(changed);
            note = note == null ? "" : note;
            svg = svg == null ? "" : svg;
            document = document == null ? SectionDocument.empty() : document;
        }

        public Revision(long number, long parent, long at, Origin origin, String note, List<String> changed,
                        SectionDocument document, String svg) {
            this(number, parent, at, origin, note, changed, document, svg, 0);
        }

        Revision closing(int number) {
            return new Revision(this.number, parent, at, origin, note, changed, document, svg, number);
        }
    }

    /** Eine Auflage eines Buchs: Nummer, Zeit ihres letzten Stands, die Revision dazu; die neueste ist offen. */
    public record Edition(int number, long at, long revision, boolean current) {
    }

    /**
     * Die Auflagen (ADR 0047, Angelo 27.11.: globale Artefakte sind versioniert, ältere Auflagen bleiben ansehbar):
     * die abgeschlossenen in Reihenfolge und zuletzt die offene, an der gearbeitet wird.
     */
    public List<Edition> editions() {
        List<Edition> editions = new ArrayList<>();
        for (Revision each : revisions) {
            if (each.edition() > 0) {
                editions.add(new Edition(each.edition(), each.at(), each.number(), false));
            }
        }
        editions.add(new Edition(editions.size() + 1, current().at(), current().number(), true));
        return editions;
    }

    /** Der Stand einer Auflage (nur lesen und ausgeben); {@code null}, wenn es sie nicht gibt. */
    public Revision edition(int number) {
        for (Edition each : editions()) {
            if (each.number() == number) {
                return revision(each.revision());
            }
        }
        return null;
    }

    /** Schließt die offene Auflage mit ihrem letzten Stand ab. */
    ChatArtifact closeEdition() {
        int number = editions().size();
        List<Revision> list = new ArrayList<>(revisions);
        list.set(list.size() - 1, current().closing(number));
        return new ChatArtifact(id, kind, title, createdAt, list, chatId);
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
        return new ChatArtifact(id, kind, title, createdAt, list, chatId);
    }

    ChatArtifact withTitle(String newTitle) {
        return new ChatArtifact(id, kind, newTitle, createdAt, revisions, chatId);
    }

    /** Mit dem Chat, der es erzeugt hat (ADR 0047: eine Tatsache, keine Ordnung). */
    ChatArtifact withChat(String creator) {
        return new ChatArtifact(id, kind, title, createdAt, revisions, creator);
    }
}
