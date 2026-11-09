package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.function.Supplier;

/**
 * Die gemeinsame Kopfleiste aller Reiter einer Seite (Analysen-Unterreiter wie Seitenreiter): EIN Aufbau, damit jede
 * Ansicht ihre Bedienelemente an derselben Stelle hat und keine Knopfzeilen unter dem Inhalt braucht.
 *
 * <pre>
 *   Titel  [Abzeichen]  Hinweis …                [Suche/Filter] [Aktionen …] [Kopieren] [Stand]
 * </pre>
 *
 * Links der Titel (fett), daneben kleine Abzeichen (etwa „Experimentell“) und ein gedämpfter Hinweis; rechts, immer in
 * dieser Reihenfolge: das Such- oder Filterfeld, die Aktionen des Reiters, der Kopieren-Knopf ({@link CopyButton}) und
 * ganz rechts das Abzeichen mit dem Stand („Aktuell“, „Nicht durchgeführt“). Was ein Reiter nicht braucht, lässt er
 * weg; die Reihenfolge der übrigen bleibt.
 */
public final class TabHeader extends JPanel {

    private final JLabel title = new JLabel(" ");
    private final JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JPanel search = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final JLabel status = statusBadge(" ", Comic.MUTED);
    private CopyButton copy;

    public TabHeader(String heading) {
        super(new BorderLayout(12, 0));
        setOpaque(false);
        title.setText(heading);
        title.setFont(Comic.semiBold(15f));
        title.setForeground(Comic.INK);
        left.setOpaque(false);
        left.add(title);
        add(left, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        for (JPanel part : new JPanel[] {search, actions, tools}) {
            part.setOpaque(false);
            right.add(part);
        }
        status.setVisible(false);
        right.add(status);
        add(right, BorderLayout.EAST);
    }

    /** Ändert den Titel. */
    public void title(String heading) {
        title.setText(heading);
    }

    /** Ein kleines Abzeichen neben dem Titel, etwa „Experimentell“. */
    public TabHeader badge(String text, Color tone) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(11f));
        label.setForeground(tone);
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tone, 1, true),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        left.add(label);
        return this;
    }

    /** Ein gedämpfter Hinweis rechts neben Titel und Abzeichen. */
    public TabHeader note(String text) {
        left.add(Comic.muted(text));
        return this;
    }

    /** Das Such- oder Filterfeld des Reiters, rechts vor den Aktionen; eines je Reiter. */
    public TabHeader search(JComponent field) {
        search.removeAll();
        search.add(field);
        return this;
    }

    /** Aktionen des Reiters (Knöpfe), rechts zwischen Suchfeld und Kopieren-Knopf. */
    public TabHeader action(Component... buttons) {
        for (Component button : buttons) {
            actions.add(button);
        }
        return this;
    }

    /** Der Kopieren-Knopf: kopiert {@code text.get()}, den Inhalt des Reiters als Text, in die Zwischenablage. */
    public TabHeader copy(Supplier<String> text) {
        if (copy != null) {
            tools.remove(copy);
        }
        copy = new CopyButton(text);
        tools.add(copy, 0);
        return this;
    }

    /** Weitere Symbolknöpfe ganz rechts, vor dem Stand (etwa Export). */
    public TabHeader tool(Component... buttons) {
        for (Component button : buttons) {
            tools.add(button);
        }
        return this;
    }

    /** Der Stand des Reiters als Abzeichen ganz rechts; {@code null} blendet es aus. */
    public void status(String text, Color tone) {
        status.setVisible(text != null);
        status.setText(text == null ? " " : text);
        tone(status, tone == null ? Comic.MUTED : tone);
    }

    /** Ein Abzeichen im Stil des Stands: Text in Farbe mit feinem Rahmen derselben Farbe. */
    public static JLabel statusBadge(String text, Color tone) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(12f));
        label.setOpaque(true);
        tone(label, tone);
        return label;
    }

    /** Färbt ein Abzeichen aus {@link #badge} um. */
    public static void tone(JLabel badge, Color tone) {
        badge.setForeground(tone);
        badge.setBackground(Comic.SURFACE);
        badge.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tone, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
    }

    // ---- für Tests

    public String statusText() {
        return status.isVisible() ? status.getText() : null;
    }

    public CopyButton copyButton() {
        return copy;
    }
}
