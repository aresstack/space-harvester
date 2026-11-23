package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;

/**
 * Die Index-Leiste des NDV-Bereichs wie die {@code IndexingSidebar} aus MainframeMate: Pfad und Bereich, Zustand des
 * Zwischenspeichers (Ampel), Fortschritt des Vorabladens, „Auswahl zwischenspeichern“ und „Cache leeren“; darunter der
 * Filterzustand der Auswahl mit Freigeben, Sperren, Filter entfernen; zuletzt die Suche im zwischengespeicherten
 * Quelltext der Bibliothek.
 */
final class NdvIndexPanel extends JPanel {

    /** Zustand für die Anzeige: Satz, Farbe (Ampel), Anzahl, Zeitpunkt. */
    record Status(String text, Color color, String count, String when) {
    }

    private final JLabel path = Comic.muted("-");
    private final JLabel scope = Comic.muted("-");
    private final JLabel status = new JLabel("-");
    private final JLabel count = Comic.muted("-");
    private final JLabel when = Comic.muted("-");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel filter = Comic.muted("-");
    final ComposerButton cacheSelection = Comic.button("Auswahl zwischenspeichern",
            "Quelltexte der Auswahl laden und analysieren; ohne Auswahl alle sichtbaren");
    final ComposerButton cancel = Comic.button("Abbrechen", "Zwischenspeichern anhalten; Geladenes bleibt");
    final ComposerButton clearCache = Comic.danger("Cache leeren",
            "Zwischengespeicherte Quelltexte, Suche und Abhängigkeiten dieser Bibliothek verwerfen");
    final ComposerButton whitelist = Comic.button("Freigeben", "Auswahl auf die Freigabeliste setzen");
    final ComposerButton blacklist = Comic.button("Sperren", "Auswahl auf die Sperrliste setzen");
    final ComposerButton removeFilter = Comic.button("Filter entfernen", "Auswahl aus beiden Listen nehmen");
    final PlaceholderField search = new PlaceholderField(14, "Im Quelltext suchen");
    private final DefaultListModel<NdvSourceCache.Hit> hits = new DefaultListModel<>();
    final JList<NdvSourceCache.Hit> hitList = new JList<>(hits);
    private final JLabel hitNote = Comic.muted(" ");

    NdvIndexPanel(Consumer<NdvSourceCache.Hit> openHit) {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 10, 8, 12)));
        setPreferredSize(new Dimension(290, 0));
        getAccessibleContext().setAccessibleName("Index");

        JPanel rows = new JPanel(new GridBagLayout());
        rows.setOpaque(false);
        int y = 0;
        y = heading(rows, y, "Zwischenspeicher");
        y = row(rows, y, "Pfad", path);
        y = row(rows, y, "Bereich", scope);
        status.setFont(Comic.semiBold(13f));
        y = row(rows, y, "Zustand", status);
        y = row(rows, y, "Objekte", count);
        y = row(rows, y, "Zuletzt", when);
        progress.setStringPainted(true);
        progress.setVisible(false);
        y = wide(rows, y, progress);
        y = wide(rows, y, Comic.row(cacheSelection, cancel));
        y = wide(rows, y, Comic.row(clearCache));
        y = heading(rows, y, "Sperrliste");
        y = row(rows, y, "Auswahl", filter);
        y = wide(rows, y, Comic.row(whitelist, blacklist));
        y = wide(rows, y, Comic.row(removeFilter));
        y = heading(rows, y, "Suche im Quelltext");
        Comic.style(search);
        y = wide(rows, y, InputFrame.search(search));
        cancel.setEnabled(false);

        hitList.setFont(Comic.regular(12.5f));
        hitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        hitList.setSelectionBackground(Comic.YELLOW);
        hitList.putClientProperty("html.disable", Boolean.TRUE);
        hitList.getAccessibleContext().setAccessibleName("Treffer");
        hitList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, false);
                if (value instanceof NdvSourceCache.Hit hit) {
                    setText(hit.name() + ":" + hit.line() + "  " + hit.text());
                    setToolTipText(hit.library() + "/" + hit.name() + "." + hit.extension() + ", Zeile " + hit.line()
                            + ": " + hit.text());
                }
                return this;
            }
        });
        hitList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && hitList.getSelectedValue() != null) {
                    openHit.accept(hitList.getSelectedValue());
                }
            }
        });
        hitList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ndv-open-hit");
        hitList.getActionMap().put("ndv-open-hit", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (hitList.getSelectedValue() != null) {
                    openHit.accept(hitList.getSelectedValue());
                }
            }
        });
        JPanel results = new JPanel(new BorderLayout(0, 4));
        results.setOpaque(false);
        results.add(hitNote, BorderLayout.NORTH);
        javax.swing.JScrollPane hitScroll = Comic.scroll(hitList);
        // Lange Zeilen werden abgeschnitten (ganz im Tooltip), kein waagerechter Balken.
        hitScroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        results.add(hitScroll, BorderLayout.CENTER);
        add(rows, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);
    }

    private static int heading(JPanel panel, int y, String text) {
        JLabel label = Comic.caption(text);
        label.setBorder(BorderFactory.createEmptyBorder(y == 0 ? 0 : 10, 0, 2, 0));
        return wide(panel, y, label);
    }

    private static int row(JPanel panel, int y, String label, JComponent value) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        JLabel text = Comic.muted(label);
        panel.add(text, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 0);
        panel.add(value, c);
        return y + 1;
    }

    private static int wide(JPanel panel, int y, JComponent value) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 0);
        panel.add(value, c);
        return y + 1;
    }

    void place(String pathText, String scopeText) {
        path.setText(pathText);
        path.setToolTipText(pathText);
        scope.setText(scopeText);
        scope.setToolTipText(scopeText);
    }

    void status(Status value) {
        status.setText(value.text());
        status.setForeground(value.color());
        count.setText(value.count());
        when.setText(value.when());
    }

    void progress(int current, int total) {
        progress.setVisible(total > 0);
        progress.setMaximum(Math.max(1, total));
        progress.setValue(current);
        progress.setString(current + " / " + total);
        cancel.setEnabled(total > 0);
    }

    void done() {
        progress.setVisible(false);
        cancel.setEnabled(false);
    }

    void filter(String text) {
        filter.setText(text);
        filter.setToolTipText(text);
    }

    void hits(List<NdvSourceCache.Hit> found, String needle, int limit) {
        hits.clear();
        found.forEach(hits::addElement);
        hitNote.setText(needle.isBlank() ? " " : found.isEmpty() ? "Keine Treffer im Zwischenspeicher"
                : found.size() >= limit ? "Erste " + limit + " Treffer" : found.size() + " Treffer");
    }

    // Für Tests
    String statusText() {
        return status.getText();
    }

    String countText() {
        return count.getText();
    }

    String filterText() {
        return filter.getText();
    }

    int hitCount() {
        return hits.size();
    }
}
