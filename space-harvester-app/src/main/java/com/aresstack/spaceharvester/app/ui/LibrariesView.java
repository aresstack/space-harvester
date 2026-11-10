package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.List;

/**
 * Einstellungen › Bibliotheken: die Open-Source-Bibliotheken, die die App ausliefert, mit Lizenz; übernommen aus
 * {@code OpenSourceLibrariesView} in aresstack/askai (Hilfe › Über). Je Zeile links die Lizenz in einer festen
 * Spalte, rechts Name und Version, darunter Maven-Gruppe und das Modul, das die Bibliothek braucht. Ein Filterfeld
 * oben rechts grenzt die Liste auf Name, Gruppe, Lizenz oder Modul ein; lange Lizenznamen brechen um.
 */
final class LibrariesView {

    static final String HEADING = "Bibliotheken und Lizenzen";
    /** Breite der Lizenzspalte: „The Apache Software License,“ passt in eine Zeile, Längeres bricht am Wort um. */
    static final int LICENSE_WIDTH = 200;
    private static final int GAP = 16;

    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final JPanel rows = new JPanel(new GridBagLayout());
    private final PlaceholderField filter = new PlaceholderField(18, "Filtern …");
    private final JLabel count = Comic.muted("");
    private final List<OssLibrary> libraries;
    private final String failure;

    LibrariesView() {
        List<OssLibrary> all;
        String problem = null;
        try {
            all = OssLibraries.read();
        } catch (IOException e) {
            all = List.of();
            problem = e.getMessage();
        }
        libraries = all;
        failure = problem;
        root.setOpaque(false);
        rows.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setOpaque(false);
        JPanel lines = new JPanel(new BorderLayout(0, 2));
        lines.setOpaque(false);
        lines.add(Comic.hint("Open-Source-Bibliotheken, die diese App mitbringt, mit ihrer Lizenz; die Liste "
                + "entsteht beim Bauen aus den Abhängigkeiten. Space Harvester selbst steht unter der MIT-Lizenz."),
                BorderLayout.CENTER);
        lines.add(count, BorderLayout.SOUTH);
        head.add(lines, BorderLayout.CENTER);
        if (failure == null) {
            InputFrame search = InputFrame.search(filter);
            search.setPreferredSize(new Dimension(220, search.getPreferredSize().height));
            JPanel east = new JPanel(new BorderLayout());
            east.setOpaque(false);
            east.add(search, BorderLayout.NORTH);
            head.add(east, BorderLayout.EAST);
            filter.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    render();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    render();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    render();
                }
            });
        }
        root.add(head, BorderLayout.NORTH);
        root.add(rows, BorderLayout.CENTER);
        render();
    }

    JComponent component() {
        return root;
    }

    /** Das Filterfeld oben rechts, für Tests. */
    PlaceholderField filter() {
        return filter;
    }

    /** Die Bibliotheken des Berichts; leer, wenn er fehlt. */
    List<OssLibrary> libraries() {
        return libraries;
    }

    /** Die gerade gezeigten Bibliotheken (nach Filter). */
    List<OssLibrary> shown() {
        return libraries.stream().filter(library -> library.matches(filter.getText())).toList();
    }

    private void render() {
        rows.removeAll();
        if (failure != null) {
            count.setText("");
            rows.add(Comic.wrapped(failure, Comic.RED, 12.5f), fill(0));
            root.revalidate();
            root.repaint();
            return;
        }
        List<OssLibrary> shown = shown();
        count.setText(shown.size() == libraries.size()
                ? Texts.count(libraries.size(), "Bibliothek", "Bibliotheken")
                : shown.size() + " von " + Texts.count(libraries.size(), "Bibliothek", "Bibliotheken"));
        int y = 0;
        if (shown.isEmpty()) {
            rows.add(Comic.hint("Keine Bibliothek passt zum Filter."), fill(y++));
        } else {
            addRow(y++, heading("Lizenz"), heading("Bibliothek"));
            for (OssLibrary library : shown) {
                addRow(y++, license(library), library(library));
            }
        }
        GridBagConstraints glue = new GridBagConstraints();
        glue.gridx = 0;
        glue.gridy = y;
        glue.weighty = 1;
        rows.add(Box.createGlue(), glue);
        root.revalidate();
        root.repaint();
    }

    private static GridBagConstraints fill(int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        return c;
    }

    private void addRow(int y, JComponent left, JComponent right) {
        // Die Lizenzspalte ist fest breit, die Bibliotheksspalte nimmt den Rest.
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(4, 0, 4, GAP);
        rows.add(column(left), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 0, 4, 0);
        rows.add(right, c);
    }

    private static JLabel heading(String text) {
        JLabel label = Comic.muted(text);
        label.setFont(Comic.semiBold(12f));
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        return label;
    }

    /**
     * Die Lizenzspalte: genau {@link #LICENSE_WIDTH} breit und so hoch, wie ihr Text dafür braucht. Ein
     * {@link WrappedText} misst seine Höhe selbst für diese Breite; ein HTML-Label mit fester Breite schnitt lange
     * Lizenznamen rechts ab, statt sie umzubrechen.
     */
    private static JComponent column(JComponent inner) {
        JPanel column = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                int height = inner instanceof WrappedText text ? text.heightFor(LICENSE_WIDTH)
                        : inner.getPreferredSize().height;
                return new Dimension(LICENSE_WIDTH, height);
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
        column.setOpaque(false);
        column.add(inner, BorderLayout.CENTER);
        return column;
    }

    private static JComponent license(OssLibrary library) {
        return Comic.wrapped(library.license(), Comic.INK, 12.5f);
    }

    private static JComponent library(OssLibrary library) {
        JPanel panel = new JPanel(new BorderLayout(0, 1));
        panel.setOpaque(false);
        JLabel name = new JLabel("<html><b>" + escape(library.artifact()) + "</b>&nbsp;&nbsp;"
                + escape(library.version()) + "</html>");
        name.setFont(Comic.regular(12.5f));
        name.setForeground(Comic.INK);
        panel.add(name, BorderLayout.NORTH);
        String detail = library.group()
                + (library.module().isEmpty() ? "" : "  ·  verwendet von " + library.module());
        WrappedText group = Comic.hint(detail);
        group.setFont(Comic.regular(11.5f));
        panel.add(group, BorderLayout.CENTER);
        return panel;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
