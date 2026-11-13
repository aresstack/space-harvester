package com.aresstack.spaceharvester.application.assets;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ein Block eines {@link StructuredDocument} in Lesereihenfolge (ADR 0026, Punkt 3). {@code text} ist reiner Text ohne
 * Auszeichnung; Verweisziele stehen getrennt in {@link #links()}. Die Hierarchie ergibt sich allein aus den typisierten
 * Feldern (Überschriften- und Listenebene), einen Dokumentbaum gibt es nicht.
 */
public sealed interface Block {

    /** Position in Lesereihenfolge ab 0, lückenlos je Dokument. */
    long index();

    /** Fundstelle, nur wenn der Extraktor sie aus der Datei liest. */
    Optional<Location> location();

    /** Verweisziele des Blocks. */
    List<Link> links();

    /** Überschrift der Ebene 1 bis 6. */
    record Heading(long index, Optional<Location> location, List<Link> links, int level, String text)
            implements Block {

        public Heading {
            location = checked(index, location);
            links = List.copyOf(links == null ? List.of() : links);
            text = Objects.requireNonNull(text, "text");
            if (level < 1 || level > 6) {
                throw new IllegalArgumentException("Überschriftenebene 1 bis 6");
            }
        }
    }

    /** Absatz; auch Kopf- und Fußzeilen, Notizen und Kommentare, bis ein Extraktor sie verlässlich unterscheidet. */
    record Paragraph(long index, Optional<Location> location, List<Link> links, String text) implements Block {

        public Paragraph {
            location = checked(index, location);
            links = List.copyOf(links == null ? List.of() : links);
            text = Objects.requireNonNull(text, "text");
        }
    }

    /** Listenpunkt, Ebene ab 1. */
    record ListItem(long index, Optional<Location> location, List<Link> links, boolean ordered, int level,
                    String text) implements Block {

        public ListItem {
            location = checked(index, location);
            links = List.copyOf(links == null ? List.of() : links);
            text = Objects.requireNonNull(text, "text");
            if (level < 1) {
                throw new IllegalArgumentException("Listenebene ab 1");
            }
        }
    }

    /**
     * Tabelle oder ein Stück davon: Eine übergroße Tabelle wird an Zeilen in mehrere Blöcke geteilt (ADR 0026, Punkt 8);
     * die Folgestücke tragen {@code continued}, die Zeilennummern der Zellen zählen über alle Stücke weiter.
     */
    record Table(long index, Optional<Location> location, List<Link> links, List<Cell> cells, boolean continued)
            implements Block {

        public Table {
            location = checked(index, location);
            links = List.copyOf(links == null ? List.of() : links);
            cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        }
    }

    /** Programmcode oder vorformatierter Text. */
    record Code(long index, Optional<Location> location, List<Link> links, String text, Optional<String> language)
            implements Block {

        public Code {
            location = checked(index, location);
            links = List.copyOf(links == null ? List.of() : links);
            text = Objects.requireNonNull(text, "text");
            language = language == null ? Optional.empty() : language.filter(name -> !name.isBlank());
        }
    }

    /**
     * Zelle einer {@link Table}.
     *
     * @param row     Zeile in der Tabelle ab 0 (Lesereihenfolge, nicht die Zeile im Blatt)
     * @param column  Spalte ab 0
     * @param header  ob die Zelle Kopfzelle ist
     * @param cellRef Zellbezug im Blatt wie {@code D17}, nur wenn der Extraktor ihn aus der Datei liest
     */
    record Cell(long row, int column, String text, boolean header, Optional<String> cellRef) {

        public Cell {
            text = Objects.requireNonNull(text, "text");
            cellRef = cellRef == null ? Optional.empty() : cellRef.filter(ref -> !ref.isBlank());
            if (row < 0 || column < 0) {
                throw new IllegalArgumentException("Zeile und Spalte ab 0");
            }
        }
    }

    private static Optional<Location> checked(long index, Optional<Location> location) {
        if (index < 0) {
            throw new IllegalArgumentException("Position ab 0");
        }
        return location == null ? Optional.empty() : location;
    }
}
