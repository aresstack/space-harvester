package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.scope.Fence;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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

    /** Merkt sich die gezogene Fenstergröße je Formular ({@link Dialogs#formOverlaySized}). */
    static final String SIZE_KEY = "basket-research";
    static final String BOOK_SIZE_KEY = "book-research";
    /** Ohne gemerkte Größe so viel des nutzbaren Bildschirms. */
    static final double SCREEN_SHARE = 0.6;
    /** Aufgeklappt bekommen die Felder unter „Erweitert“ zusammen so viel Höhe wie der Auftrag mal diesen Faktor. */
    private static final double MORE_WEIGHT = 2;
    private static final int COLUMNS = 50;

    final JTextArea mission = area(8);
    final JTextArea edge = area(3);
    final JTextArea included = area(4);
    final JTextArea excluded = area(4);
    private final JButton advanced = Comic.disclosure("Erweitert",
            "Rand des Themas, was ausdrücklich dazugehört und was ausgeschlossen ist");
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

    /**
     * Fragt nach Auftrag und Weidezaun; leer, wenn der Benutzer abbricht oder keinen Auftrag nennt. Das Fenster ist
     * vergrößerbar und merkt sich die gezogene Größe; die Felder wachsen mit.
     */
    Optional<Fence> ask(Component parent, String folderName) {
        boolean start = Dialogs.formOverlaySized(parent, (book ? BOOK_TITLE : TITLE) + " – " + folderName,
                body(folderName), book ? BOOK_ACTION : ACTION, book ? BOOK_SIZE_KEY : SIZE_KEY, SCREEN_SHARE);
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
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        int row = 0;
        row = add(body, row, Comic.note(book ? "Aus den Seiten im Ordner „" + folderName + "“ entsteht ein Buch zum "
                + "Auftrag. Zuerst beschreibt die KI die Quellen und siebt die Begriffe; danach wählst du die Begriffe, "
                + "die eigene Fragen bekommen, und startest das Schreiben. Buch und Missionsbericht kommen in den "
                + "Ausgabeordner. Fortschritt unter Aufträge."
                : "Die Recherche sucht im Wissensbestand Seiten zum Auftrag, prüft jede am "
                + "Weidezaun und legt passende als Favoriten in den Ordner „" + folderName + "“. Seiten, die schon im "
                + "Ordner liegen, liest sie nicht noch einmal. Fortschritt unter Aufträge."), 0);
        row = add(body, row, label("Auftrag"), 0);
        row = add(body, row, field(mission), 1);
        row = add(body, row, advanced, 0);
        JPanel more = new JPanel(new GridBagLayout());
        more.setOpaque(false);
        int inner = 0;
        inner = add(more, inner, label("Rand des Themas"), 0);
        inner = add(more, inner, field(edge), 1);
        inner = add(more, inner, label("Dazu gehört ausdrücklich (eine Angabe je Zeile)"), 0);
        inner = add(more, inner, field(included), 1);
        inner = add(more, inner, label("Ausgeschlossen (eine Angabe je Zeile)"), 0);
        inner = add(more, inner, field(excluded), 1);
        if (book) {
            inner = add(more, inner, label("Länge in Wörtern (leer: so lang, wie die Belege tragen)"), 0);
            Comic.style(length);
            JPanel short_ = new JPanel(new BorderLayout());
            short_.setOpaque(false);
            short_.add(length, BorderLayout.WEST);
            add(more, inner, short_, 0);
        }
        more.setVisible(false);
        GridBagConstraints place = constraints(row, 0);
        body.add(more, place);
        advanced.addActionListener(e -> {
            boolean open = !Comic.disclosed(advanced);
            Comic.disclose(advanced, open);
            more.setVisible(open);
            // Aufgeklappt teilen sich die Felder die Höhe; Auftrag und Erweitert wachsen gleich mit.
            body.remove(more);
            body.add(more, constraints(place.gridy, open ? MORE_WEIGHT : 0));
            grow(body);
            body.revalidate();
            body.repaint();
        });
        return body;
    }

    /**
     * Ist das Fenster für die aufgeklappten Felder zu niedrig, wird es so hoch, dass jedes Feld seine Zeilen zeigt
     * (höchstens der Bildschirm, {@link Dialogs#present}); kleiner wird es nie von selbst.
     */
    private static void grow(JComponent body) {
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(body);
        if (window == null) {
            return;
        }
        int missing = body.getPreferredSize().height - body.getHeight();
        if (missing <= 0) {
            return;
        }
        java.awt.Rectangle screen = window.getGraphicsConfiguration() == null ? null
                : window.getGraphicsConfiguration().getBounds();
        int height = window.getHeight() + missing;
        if (screen != null) {
            height = Math.min(height, screen.height);
        }
        window.setSize(window.getWidth(), height);
        if (screen != null && window.getY() + height > screen.y + screen.height) {
            window.setLocation(window.getX(), Math.max(screen.y, screen.y + screen.height - height));
        }
    }

    private static int add(JPanel panel, int row, JComponent component, double weight) {
        panel.add(component, constraints(row, weight));
        return row + 1;
    }

    private static GridBagConstraints constraints(int row, double weight) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.weighty = weight;
        c.fill = weight > 0 ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(weight > 0 ? 2 : 6, 0, 0, 0);
        return c;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(12.5f));
        label.setForeground(Comic.INK);
        return label;
    }

    /** Ein mehrzeiliges Feld mit Feldrahmen; es zeigt immer mindestens seine Zeilen und wächst mit dem Fenster. */
    private static JComponent field(JTextArea area) {
        javax.swing.JScrollPane scroll = Comic.scroll(area);
        scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(Comic.fieldBorder());
        scroll.getViewport().setBackground(java.awt.Color.WHITE);
        Dimension rows = area.getPreferredScrollableViewportSize();
        Insets frame = scroll.getInsets();
        Dimension least = new Dimension(0, rows.height + frame.top + frame.bottom);
        scroll.setMinimumSize(least);
        scroll.setPreferredSize(new Dimension(rows.width, least.height));
        return scroll;
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
        JTextArea area = new JTextArea(rows, COLUMNS);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        Comic.style(area);
        area.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6));
        return area;
    }
}
