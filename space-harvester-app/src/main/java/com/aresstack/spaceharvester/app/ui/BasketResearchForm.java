package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.scope.Fence;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Die Eingabe zu „Korb füllen“ (Buchfunktion Phase 3) und „Buch schreiben“ (Phase 4, Arbeitszweig): sichtbar nur der
 * Auftrag, die Entscheidung des Benutzers; Rand des Themas, was ausdrücklich dazugehört und was ausgeschlossen ist (beim
 * Buch auch die Länge), stehen unter „Erweitert“. Daraus wird der Weidezaun. Was zum Ordner schon gespeichert ist,
 * steht vorab in den Feldern ({@link #prefill}). Die Eingabe ist ein Overlay wie die übrigen Formulare
 * ({@link Dialogs#formOverlay}).
 */
final class BasketResearchForm {

    static final String TITLE = "Korb füllen";
    static final String ACTION = "Recherche starten";
    static final String BOOK_TITLE = "Buch schreiben";
    static final String BOOK_ACTION = "Vorbereiten";

    final JTextArea mission = area(4);
    final JTextArea edge = area(2);
    final JTextArea included = area(3);
    final JTextArea excluded = area(3);
    private final JButton advanced = Comic.disclosure("Erweitert",
            "Rand des Themas, was ausdrücklich dazugehört und was ausgeschlossen ist");
    private final JPanel more = new JPanel();
    /** Nur beim Buch: gewünschte Länge in Wörtern, leer = nach den Belegen. */
    final javax.swing.JTextField length = new javax.swing.JTextField(8);
    private final boolean book;

    BasketResearchForm() {
        this(false);
    }

    private BasketResearchForm(boolean book) {
        this.book = book;
    }

    /** Die Eingabe zu „Buch schreiben“. */
    static BasketResearchForm book() {
        return new BasketResearchForm(true);
    }

    /** Setzt Auftrag, Rand, Ein- und Ausschlüsse und die Länge (0 = leer) vorab. */
    BasketResearchForm prefill(Fence fence, int targetWords) {
        mission.setText(fence.getMission());
        edge.setText(fence.getEdge());
        included.setText(String.join("\n", fence.getIncluded()));
        excluded.setText(String.join("\n", fence.getExcluded()));
        length.setText(targetWords > 0 ? Integer.toString(targetWords) : "");
        return this;
    }

    /** Fragt nach Auftrag und Weidezaun; leer, wenn der Benutzer abbricht oder keinen Auftrag nennt. */
    Optional<Fence> ask(Component parent, String folderName) {
        boolean start = Dialogs.formOverlay(parent, (book ? BOOK_TITLE : TITLE) + " – " + folderName,
                body(folderName), book ? BOOK_ACTION : ACTION);
        return start ? fence() : Optional.empty();
    }

    /** Die gewünschte Länge in Wörtern; 0, wenn leer oder keine Zahl (dann nach den Belegen). */
    int targetWords() {
        try {
            return Math.max(0, Integer.parseInt(length.getText().strip().replace(".", "")));
        } catch (NumberFormatException none) {
            return 0;
        }
    }

    JComponent body(String folderName) {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(left(Comic.note(book ? "Aus den Seiten im Ordner „" + folderName + "“ entsteht ein Buch zum Auftrag. "
                + "Zuerst beschreibt die KI die Quellen und siebt die Begriffe; danach wählst du die Begriffe, die "
                + "eigene Fragen bekommen, und startest das Schreiben. Buch und Missionsbericht kommen in den "
                + "Ausgabeordner. Fortschritt unter Aufträge."
                : "Die Recherche sucht im Wissensbestand Seiten zum Auftrag, prüft jede am "
                + "Weidezaun und legt passende als Favoriten in den Ordner „" + folderName + "“. Seiten, die schon im "
                + "Ordner liegen, liest sie nicht noch einmal. Fortschritt unter Aufträge.")));
        body.add(left(new JLabel("Auftrag")));
        body.add(left(Comic.scroll(mission)));
        body.add(left(advanced));
        more.setOpaque(false);
        more.setLayout(new BoxLayout(more, BoxLayout.Y_AXIS));
        more.add(left(new JLabel("Rand des Themas")));
        more.add(left(Comic.scroll(edge)));
        more.add(left(new JLabel("Dazu gehört ausdrücklich (eine Angabe je Zeile)")));
        more.add(left(Comic.scroll(included)));
        more.add(left(new JLabel("Ausgeschlossen (eine Angabe je Zeile)")));
        more.add(left(Comic.scroll(excluded)));
        if (book) {
            more.add(left(new JLabel("Länge in Wörtern (leer: so lang, wie die Belege tragen)")));
            length.setFont(Comic.regular(12.5f));
            length.setMaximumSize(length.getPreferredSize());
            more.add(left(length));
        }
        more.setVisible(false);
        advanced.addActionListener(e -> {
            boolean open = !Comic.disclosed(advanced);
            Comic.disclose(advanced, open);
            more.setVisible(open);
            body.revalidate();
        });
        body.add(more);
        return body;
    }

    /** Der Weidezaun aus den Feldern; leer ohne Auftrag. */
    Optional<Fence> fence() {
        String text = mission.getText().strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Fence(text, edge.getText().strip(), lines(included), lines(excluded), "", ""));
    }

    private static List<String> lines(JTextArea area) {
        return Arrays.stream(area.getText().split("\\R")).map(String::strip).filter(line -> !line.isEmpty())
                .toList();
    }

    private static JTextArea area(int rows) {
        JTextArea area = new JTextArea(rows, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Comic.regular(12.5f));
        return area;
    }

    private static <T extends JComponent> T left(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (component instanceof javax.swing.JScrollPane) {
            component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
        }
        return component;
    }
}
