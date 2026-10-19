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
    static final String NOT_RELEASED = "Diese Quelle ist nicht für den KI-Dienst freigegeben (Quellenformular › "
            + "„Inhalte dieser Quelle dürfen an den KI-Dienst gehen“); ihre Bilder werden nicht beschrieben.";
    static final String DESCRIBE = "Bilder beschreiben";

    private final JLabel state = new JLabel(" ");
    private final JPanel list = new JPanel();
    private final JLabel hint = Comic.muted(" ");
    private final List<String> lines = new ArrayList<>();
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton describe = Comic.button(DESCRIBE,
            "Lässt die Bilder dieser Seite vom Vision-Modell beschreiben; läuft im Hintergrund");
    private Runnable onDescribe = () -> { };

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
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.add(describe);
        head.add(actions, BorderLayout.SOUTH);
        describe.addActionListener(e -> onDescribe.run());
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(list, BorderLayout.NORTH);
        body.add(hint, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(Comic.scroll(body), BorderLayout.CENTER);
        show(PassagesTab.State.MISSING, null, List.of());
    }

    /** {@code listener} startet die Beschreibung im Hintergrund; nur auf Abruf, nie beim bloßen Anzeigen. */
    void onDescribe(Runnable listener) {
        onDescribe = Objects.requireNonNull(listener, "listener");
    }

    /** Sperrt „Bilder beschreiben“, solange eine Beschreibung läuft. */
    void describing(boolean running) {
        describe.setEnabled(!running);
    }

    /** Solange die gespeicherten Beschreibungen laden: kein Stand, keine Bilder, „Bilder beschreiben“ gesperrt. */
    void loading() {
        describing(true);
        state.setText("Wird geladen …");
        state.setForeground(Comic.MUTED);
        state.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.MUTED, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        list.removeAll();
        lines.clear();
        hint.setText(" ");
        list.revalidate();
        list.repaint();
    }

    /** Wie {@link #show(PassagesTab.State, String, List, List)} ohne Warnungen des Core. */
    void show(PassagesTab.State current, String reason, List<Image> images) {
        show(current, reason, List.of(), images);
    }

    /**
     * Zeigt die Bilder; bei {@link PassagesTab.State#STALE} die alten Beschreibungen, als „Veraltet“ markiert.
     *
     * @param reason   Grund des Core für den Zustand ({@code vision-model-missing}, {@code source-not-released},
     *                 {@code not-analysed}, bei STALE {@code attachment-changed}) oder {@code null}
     * @param warnings Hinweise des Core ({@code PageImages.warnings()}), etwa {@code page-changed}
     */
    void show(PassagesTab.State current, String reason, List<String> warnings, List<Image> images) {
        boolean blocked = "vision-model-missing".equals(reason) || "source-not-released".equals(reason);
        describe.setVisible(!blocked);
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
        boolean stale = current == PassagesTab.State.STALE;
        List<Image> shown = current == PassagesTab.State.CURRENT || stale ? images : List.of();
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                list.add(Box.createVerticalStrut(6));
            }
            list.add(entry(shown.get(i), stale));
            lines.add(shown.get(i).name() + " · " + statusText(shown.get(i)) + (stale ? " · Veraltet" : ""));
        }
        hint.setText("vision-model-missing".equals(reason) ? NO_MODEL
                : "source-not-released".equals(reason) ? NOT_RELEASED
                : hint(current, reason, warnings == null ? List.of() : warnings, shown.size()));
        list.revalidate();
        list.repaint();
    }

    private JPanel entry(Image image, boolean stale) {
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
        JLabel status = Comic.muted(statusText(image) + (stale ? " · Veraltet" : ""));
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
        // Veraltete Beschreibungen bleiben lesbar, aber ausgegraut.
        description.setForeground(stale ? Comic.MUTED : Comic.INK);
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

    /** Hinweis bei {@code page-changed}: neues Beschreiben hilft erst, wenn der Bestand die Quelle eingeholt hat. */
    static final String PAGE_CHANGED = "Die Quelle ist weiter als der gespeicherte Stand; erst nach dem Abgleich der "
            + "Quelle gelten neue Beschreibungen („Aktualität prüfen“ zeigt den Unterschied).";
    /** Hinweis bei {@code attachment-changed}. */
    static final String ATTACHMENT_CHANGED = "Ein Bild wurde seither ersetzt; „" + DESCRIBE
            + "“ erneuert die Beschreibungen.";

    /** Wie {@link #hint(PassagesTab.State, int)}, bei STALE mit dem genauen Grund des Core. */
    static String hint(PassagesTab.State state, String reason, List<String> warnings, int count) {
        if (state == PassagesTab.State.STALE) {
            if (warnings.contains("page-changed")) {
                return PAGE_CHANGED;
            }
            if ("attachment-changed".equals(reason)) {
                return ATTACHMENT_CHANGED;
            }
        }
        return hint(state, count);
    }

    static String hint(PassagesTab.State state, int count) {
        return switch (state) {
            case MISSING -> "Die Bilder dieser Seite sind noch nicht beschrieben; „" + DESCRIBE + "“ startet es.";
            case STALE -> "Die Beschreibungen passen nicht mehr zur angezeigten Fassung; „" + DESCRIBE
                    + "“ erneuert sie.";
            case FAILED -> "Die letzte Bildbeschreibung ist fehlgeschlagen; „" + DESCRIBE + "“ startet sie neu.";
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

    boolean describeOffered() {
        return describe.isVisible() && describe.isEnabled();
    }

    void clickDescribe() {
        describe.doClick();
    }

    String hint() {
        return hint.getText();
    }
}
