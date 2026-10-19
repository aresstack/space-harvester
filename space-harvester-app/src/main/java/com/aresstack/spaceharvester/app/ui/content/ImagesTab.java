package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Der Reiter „Bilder“ der Leseansicht (#362): je Bild der Seite Vorschau, die Beschreibung des Vision-Modells aus der
 * Core-Analyse {@code images} und ihr Stand. Die App beschreibt nichts selbst; ohne eingerichtetes Vision-Modell steht
 * ein Hinweis statt eines Fehlers. Nur auf dem EDT.
 */
final class ImagesTab extends JPanel {

    /** Stand der Beschreibung eines Bildes. */
    enum Status { DESCRIBED, PENDING, FAILED, SKIPPED }

    /** Ein Bild, wie der Core es liefert; {@code preview} ist ein bereits geladenes Vorschaubild oder {@code null}. */
    record Image(String name, Status status, String description, String reason, Icon preview) {

        Image {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
        }
    }

    static final String NO_MODEL = "Kein Vision-Modell eingerichtet (Einstellungen › Modelle); ohne Modell werden "
            + "Bilder nicht beschrieben.";

    private final JLabel state = new JLabel(" ");
    private final JPanel list = new JPanel();
    private final JLabel hint = Comic.muted(" ");
    private final List<String> lines = new ArrayList<>();

    ImagesTab() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JLabel title = Comic.text("Bilder dieser Seite");
        title.setFont(Comic.semiBold(15f));
        head.add(title, BorderLayout.WEST);
        state.setFont(Comic.semiBold(12f));
        state.setOpaque(true);
        head.add(state, BorderLayout.EAST);
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(list, BorderLayout.NORTH);
        body.add(hint, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(Comic.scroll(body), BorderLayout.CENTER);
        show(PassagesTab.State.MISSING, List.of(), true);
    }

    /**
     * Zeigt die Bilder; außer bei {@link PassagesTab.State#CURRENT} ist {@code images} leer.
     *
     * @param modelConfigured ob ein Vision-Modell eingerichtet ist
     */
    void show(PassagesTab.State current, List<Image> images, boolean modelConfigured) {
        state.setText(stateText(current));
        Color tone = switch (current) {
            case CURRENT -> Comic.GREEN;
            case STALE -> Comic.ORANGE;
            case FAILED -> Comic.RED;
            case MISSING -> Comic.MUTED;
        };
        state.setForeground(tone);
        state.setBackground(Comic.SURFACE);
        state.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tone, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        list.removeAll();
        lines.clear();
        List<Image> shown = current == PassagesTab.State.CURRENT ? images : List.of();
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                list.add(Box.createVerticalStrut(6));
            }
            list.add(entry(shown.get(i)));
            lines.add(shown.get(i).name() + " · " + statusText(shown.get(i)));
        }
        hint.setText(!modelConfigured ? NO_MODEL : hint(current, shown.size()));
        list.revalidate();
        list.repaint();
    }

    private JPanel entry(Image image) {
        JLabel preview = new JLabel(image.preview(), SwingConstants.CENTER);
        preview.setPreferredSize(new Dimension(96, 72));
        preview.setBorder(BorderFactory.createLineBorder(Comic.MUTED, 1, true));
        if (image.preview() == null) {
            preview.setText("Keine Vorschau");
            preview.setFont(Comic.regular(11f));
            preview.setForeground(Comic.MUTED);
        }
        JLabel name = Comic.text(image.name());
        name.setFont(Comic.semiBold(13f));
        JLabel status = Comic.muted(statusText(image));
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.add(name, BorderLayout.WEST);
        head.add(status, BorderLayout.EAST);
        JTextArea description = new JTextArea(image.description() == null ? "" : image.description());
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setFont(Comic.regular(13f));
        description.setForeground(Comic.INK);
        JPanel lines = new JPanel(new BorderLayout(0, 4));
        lines.setOpaque(false);
        lines.add(head, BorderLayout.NORTH);
        lines.add(description, BorderLayout.CENTER);
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));
        row.add(preview, BorderLayout.WEST);
        row.add(lines, BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    /** „Beschrieben“, „Wartet auf Beschreibung“, „Fehlgeschlagen: …“, „Übersprungen: …“ */
    static String statusText(Image image) {
        return switch (image.status()) {
            case DESCRIBED -> "Beschrieben";
            case PENDING -> "Wartet auf Beschreibung";
            case FAILED -> image.reason() == null ? "Fehlgeschlagen" : "Fehlgeschlagen: " + image.reason();
            case SKIPPED -> image.reason() == null ? "Übersprungen" : "Übersprungen: " + image.reason();
        };
    }

    static String stateText(PassagesTab.State state) {
        return switch (state) {
            case CURRENT -> "Aktuell";
            case STALE -> "Veraltet";
            case FAILED -> "Fehlgeschlagen";
            case MISSING -> "Nicht durchgeführt";
        };
    }

    static String hint(PassagesTab.State state, int count) {
        return switch (state) {
            case MISSING -> "Noch keine Bildanalyse für diese Seite. Sie entsteht mit der Analyse beim nächsten "
                    + "Abgleich.";
            case STALE -> "Die Bildanalyse passt nicht mehr zur angezeigten Fassung; „Analyse neu berechnen“ unter "
                    + "Text erneuert sie.";
            case FAILED -> "Die letzte Bildanalyse ist fehlgeschlagen; „Analyse neu berechnen“ unter Text startet sie "
                    + "neu.";
            case CURRENT -> count == 0 ? "Diese Seite enthält keine Bilder." : " ";
        };
    }

    // ---- für Tests

    List<String> lines() {
        return List.copyOf(lines);
    }

    String stateLabel() {
        return state.getText();
    }

    String hint() {
        return hint.getText();
    }
}
