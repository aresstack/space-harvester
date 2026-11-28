package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.HintActions;
import com.aresstack.spaceharvester.app.ui.controls.TabHeader;

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
import java.util.function.Consumer;

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
    static final String DESCRIBE_ONCE = "Nur diese Seite einmalig beschreiben";
    static final String RELEASE_SOURCE = "Diese Quelle freigeben";

    private final TabHeader head = new TabHeader("Bilder dieser Seite");
    private final JPanel list = new JPanel();
    private final JLabel hint = Comic.muted(" ");
    private final List<String> lines = new ArrayList<>();
    private final List<String> copyLines = new ArrayList<>();
    /** Die Erläuterung zum Stand, ob sie im Reiter oder in der Statuszeile steht. */
    private String explanation = " ";
    private Consumer<String> onStatus = text -> { };
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton describe = Comic.button(DESCRIBE,
            "Lässt die Bilder dieser Seite vom Vision-Modell beschreiben; läuft im Hintergrund");
    private Runnable onDescribe = () -> { };
    private Runnable onDescribeOnce = () -> { };
    private Runnable onRelease = () -> { };
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton describeOnce = Comic.button(
            DESCRIBE_ONCE, "Schickt nur die Bilder dieser Seite an das Vision-Modell; die Quelle bleibt gesperrt");
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton release = Comic.button(RELEASE_SOURCE,
            "Setzt im Quellenformular „Inhalte dieser Quelle dürfen an den KI-Dienst gehen“ und beschreibt die "
                    + "Bilder dieser Seite");
    /** Quelle nicht freigegeben: Hinweis mit der Wahl, es für diese Seite oder die Quelle zu erlauben. */
    private final HintActions notReleased = new HintActions(NOT_RELEASED, describeOnce, release);
    private boolean released = true;
    /** Die Rahmen der Vorschauen in der Reihenfolge der Bilder; gefüllt werden sie nach dem Zeigen. */
    private final List<JLabel> previews = new ArrayList<>();
    /** Stand mit Knopf und Beschreibung je Zeile, damit eine fertige Beschreibung sofort erscheint. */
    private final List<JPanel> cells = new ArrayList<>();
    private final List<JTextArea> descriptions = new ArrayList<>();
    /** Die gezeigten Bilder je Zeile, mit dem Stand nach jedem eben beschriebenen Bild. */
    private final List<Image> rows = new ArrayList<>();
    /** Zeilen, deren Bild gerade beschrieben wird. */
    private final java.util.Set<Integer> running = new java.util.HashSet<>();
    /** Ob einzelne Bilder beschrieben werden dürfen (Vision-Modell da, Quelle oder Seite freigegeben). */
    private boolean single;
    private boolean stale;
    private java.util.function.BiConsumer<Integer, Boolean> onDescribeOne = (index, fresh) -> { };
    static final String DESCRIBE_ONE = "Beschreiben";
    static final String RETRY = "Erneut versuchen";
    static final String REDESCRIBE = "Neu beschreiben: ersetzt die Beschreibung dieses Bildes";
    static final String RUNNING = "Wird beschrieben …";
    static final String MODEL_LOADING = "Vision-Modell wird geladen …";
    /** Der zuletzt gezeigte Stand, für den Kopf nach dem Laden des Modells. */
    private PassagesTab.State state = PassagesTab.State.MISSING;
    private java.util.function.IntConsumer onOpen = index -> { };
    static final String PREVIEW_LOADING = "Lädt …";
    static final String NO_PREVIEW = "Keine Vorschau";

    ImagesTab() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        head.action(describe).copy(() -> String.join("\n", copyLines));
        describe.addActionListener(e -> onDescribe.run());
        describeOnce.addActionListener(e -> onDescribeOnce.run());
        release.addActionListener(e -> onRelease.run());
        notReleased.setVisible(false);
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JPanel body = new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(list, BorderLayout.NORTH);
        JPanel hints = new JPanel(new BorderLayout(0, 8));
        hints.setOpaque(false);
        hints.add(hint, BorderLayout.NORTH);
        hints.add(notReleased, BorderLayout.CENTER);
        body.add(hints, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(Comic.scroll(body), BorderLayout.CENTER);
        show(PassagesTab.State.MISSING, null, List.of());
    }

    /** {@code listener} startet die Beschreibung im Hintergrund; nur auf Abruf, nie beim bloßen Anzeigen. */
    void onDescribe(Runnable listener) {
        onDescribe = Objects.requireNonNull(listener, "listener");
    }

    /**
     * {@code listener} beschreibt nur das Bild dieser Zeile, etwa nach einem Fehler; mit {@code fresh} neu statt aus
     * den gespeicherten Beschreibungen („Neu beschreiben“).
     */
    void onDescribeOne(java.util.function.BiConsumer<Integer, Boolean> listener) {
        onDescribeOne = Objects.requireNonNull(listener, "listener");
    }

    /** Zeigt in der Zeile „Wird beschrieben …“ statt ihres Knopfes, bis {@link #update} das Ergebnis bringt. */
    void running(int index, boolean on) {
        if (index < 0 || index >= cells.size()) {
            return;
        }
        if (on) {
            running.add(index);
        } else {
            running.remove(index);
        }
        fill(index);
    }

    /**
     * Zeigt im Kopf, dass das Vision-Modell gerade geladen wird (wie AskAI „läuft“ zeigt), danach wieder den Stand.
     */
    void modelLoading(boolean on) {
        if (on) {
            head.status(MODEL_LOADING, Comic.ORANGE);
        } else {
            head.status(stateText(state), tone(state));
        }
    }

    private static Color tone(PassagesTab.State state) {
        return switch (state) {
            case CURRENT -> Comic.GREEN;
            case STALE -> Comic.ORANGE;
            case FAILED -> Comic.RED;
            case MISSING -> Comic.MUTED;
        };
    }

    /** Die Zeile wird gerade beschrieben. */
    boolean running(int index) {
        return running.contains(index);
    }

    /** {@code listener} öffnet das Bild dieser Zeile groß, etwa um seine Beschreibung zu prüfen. */
    void onOpen(java.util.function.IntConsumer listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Zeigt ein eben beschriebenes Bild in seiner Zeile, noch während die übrigen beschrieben werden: Stand,
     * Beschreibung und, wenn vorhanden, die Vorschau.
     */
    void update(int index, Image image, Icon icon) {
        if (index < 0 || index >= cells.size()) {
            return;
        }
        rows.set(index, image);
        running.remove(index);
        fill(index);
        JTextArea description = descriptions.get(index);
        description.setText(image.description() == null ? "" : image.description());
        description.setForeground(Comic.INK);
        if (icon != null) {
            preview(index, icon);
        }
        // Die Zeile wächst mit dem Text: neu auslegen bis hinauf zur Bildlaufleiste.
        description.invalidate();
        revalidate();
        repaint();
    }

    /** Die Bilder, wie sie gerade in den Zeilen stehen. */
    List<Image> rows() {
        return List.copyOf(rows);
    }

    /** Stand und Knopf einer Zeile nach ihrem Bild: Beschreiben, Erneut versuchen oder Neu beschreiben (Symbol). */
    private void fill(int index) {
        JPanel cell = cells.get(index);
        Image image = rows.get(index);
        cell.removeAll();
        if (running.contains(index)) {
            cell.add(Comic.muted(RUNNING));
        } else if (!single) {
            cell.add(Comic.muted(statusText(image) + (stale ? " · Veraltet" : "")));
        } else {
            switch (image.status()) {
                case DESCRIBED -> {
                    if (stale) {
                        cell.add(Comic.muted("Veraltet"));
                    }
                    com.aresstack.spaceharvester.app.ui.IconButton again = new com.aresstack.spaceharvester.app.ui
                            .IconButton(com.aresstack.spaceharvester.app.ui.Glyph.REFRESH, REDESCRIBE).compact();
                    again.addActionListener(e -> onDescribeOne.accept(index, true));
                    cell.add(again);
                }
                case PENDING -> cell.add(action(DESCRIBE_ONE, "Lässt nur dieses Bild vom Vision-Modell beschreiben",
                        index));
                case FAILED -> {
                    cell.add(Comic.muted(statusText(image)));
                    cell.add(action(RETRY, "Versucht die Beschreibung dieses Bildes noch einmal", index));
                }
                case SKIPPED -> cell.add(Comic.muted(statusText(image)));
            }
        }
        cell.revalidate();
        cell.repaint();
    }

    private javax.swing.JButton action(String text, String tooltip, int index) {
        javax.swing.JButton button = Comic.button(text, tooltip);
        button.addActionListener(e -> onDescribeOne.accept(index, false));
        return button;
    }

    /** Füllt den Rahmen der Vorschau eines Bildes; {@code null}: es gibt keine. */
    void preview(int index, Icon icon) {
        if (index < 0 || index >= previews.size()) {
            return;
        }
        JLabel preview = previews.get(index);
        preview.setIcon(icon);
        preview.setText(icon == null ? NO_PREVIEW : null);
    }

    /** {@code listener} bekommt die Erläuterung zum Stand für die Statuszeile des Hauptfensters (" " ohne). */
    void onStatus(Consumer<String> listener) {
        onStatus = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} beschreibt nur die Bilder dieser Seite, obwohl die Quelle nicht freigegeben ist. */
    void onDescribeOnce(Runnable listener) {
        onDescribeOnce = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} gibt die Quelle für den KI-Dienst frei und beschreibt dann die Bilder dieser Seite. */
    void onRelease(Runnable listener) {
        onRelease = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Ob die Quelle für das Vision-Modell freigegeben ist; vor {@link #show} setzen. Nicht freigegeben zeigt der
     * Reiter das Knopfpaar auch dann, wenn es schon Beschreibungen gibt (nach „einmalig“): „Bilder beschreiben“ wäre
     * für die gesperrte Quelle nur eine Fehlermeldung.
     */
    void released(boolean released) {
        this.released = released;
    }

    /** Sperrt „Bilder beschreiben“ und die Freigabe-Knöpfe, solange eine Beschreibung läuft. */
    void describing(boolean running) {
        describe.setEnabled(!running);
        describeOnce.setEnabled(!running);
        release.setEnabled(!running);
    }

    /** Solange die gespeicherten Beschreibungen laden: kein Stand, keine Bilder, „Bilder beschreiben“ gesperrt. */
    void loading() {
        describing(true);
        head.status("Wird geladen …", Comic.MUTED);
        list.removeAll();
        lines.clear();
        copyLines.clear();
        hint.setText(" ");
        notReleased.setVisible(false);
        explanation = " ";
        onStatus.accept(" ");
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
        boolean unreleased = "source-not-released".equals(reason) || !released;
        describe.setVisible(!"vision-model-missing".equals(reason) && !unreleased);
        single = describe.isVisible();
        notReleased.setVisible(unreleased);
        state = current;
        head.status(stateText(current), tone(current));
        list.removeAll();
        previews.clear();
        cells.clear();
        descriptions.clear();
        rows.clear();
        running.clear();
        lines.clear();
        copyLines.clear();
        stale = current == PassagesTab.State.STALE;
        // Die Bilder stehen immer da, auch noch nicht beschriebene (dann ohne Text).
        List<Image> shown = images;
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                list.add(Box.createVerticalStrut(6));
            }
            list.add(entry(i, shown.get(i), stale));
            lines.add(shown.get(i).name() + " · " + statusText(shown.get(i)) + (stale ? " · Veraltet" : ""));
            copyLines.add(lines.get(lines.size() - 1));
            if (shown.get(i).description() != null && !shown.get(i).description().isBlank()) {
                copyLines.add(shown.get(i).description());
                copyLines.add("");
            }
        }
        String text = "vision-model-missing".equals(reason) ? NO_MODEL
                : "source-not-released".equals(reason) ? NOT_RELEASED
                : hint(current, reason, warnings == null ? List.of() : warnings, shown.size());
        // Ohne Bilder steht der Grund im Reiter; mit Bildern nur in der Statuszeile. Die fehlende Freigabe steht
        // über ihren Knöpfen.
        explanation = text;
        hint.setText(shown.isEmpty() && !notReleased.isVisible() ? text : " ");
        onStatus.accept(shown.isEmpty() ? " " : text);
        list.revalidate();
        list.repaint();
    }

    private JPanel entry(int index, Image image, boolean stale) {
        // Erst der Rahmen; seinen Inhalt füllt {@link #preview} im Hintergrund nach.
        JLabel preview = new JLabel(image.preview(), SwingConstants.CENTER);
        preview.setPreferredSize(new Dimension(96, 72));
        preview.setBorder(BorderFactory.createLineBorder(Comic.MUTED, 1, true));
        preview.setFont(Comic.regular(11f));
        preview.setForeground(Comic.MUTED);
        if (image.preview() == null) {
            preview.setText(PREVIEW_LOADING);
        }
        previews.add(preview);
        JLabel name = Comic.text(image.name());
        name.setFont(Comic.semiBold(13f));
        JPanel status = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        status.setOpaque(false);
        cells.add(status);
        rows.add(image);
        fill(index);
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
        descriptions.add(description);
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
        // Ein Klick öffnet das Bild groß, mit seiner Beschreibung.
        Cursors.set(row, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        row.setToolTipText("Bild groß öffnen und die Beschreibung prüfen");
        java.awt.event.MouseAdapter open = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                onOpen.accept(index);
            }
        };
        row.addMouseListener(open);
        preview.addMouseListener(open);
        name.addMouseListener(open);
        return row;
    }

    /** Lösen „Beschreiben“, „Erneut versuchen“ oder „Neu beschreiben“ einer Zeile aus (für Tests). */
    void clickDescribeOne(int index) {
        for (java.awt.Component component : cells.get(index).getComponents()) {
            if (component instanceof javax.swing.AbstractButton button) {
                button.doClick();
                return;
            }
        }
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
            case STALE -> com.aresstack.spaceharvester.app.ui.controls.TabHeader.STALE;
            case FAILED -> "Fehlgeschlagen";
            case MISSING -> "Nicht durchgeführt";
        };
    }

    /** Hinweis bei {@code page-changed}: neues Beschreiben hilft erst, wenn der Bestand die Quelle eingeholt hat. */
    static final String PAGE_CHANGED = "Die Quelle ist weiter als der gespeicherte Stand; erst nach dem Abgleich der "
            + "Quelle gelten neue Beschreibungen („Aktualität prüfen“ zeigt den Unterschied).";
    /** Hinweis bei {@code attachment-changed}. */
    static final String ATTACHMENT_CHANGED = "Ein Bild wurde seither ersetzt oder entfernt; „" + DESCRIBE
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
        return head.statusText();
    }

    boolean describeOffered() {
        return describe.isVisible() && describe.isEnabled();
    }

    boolean releaseOffered() {
        return notReleased.isVisible() && describeOnce.isEnabled();
    }

    void clickDescribeOnce() {
        describeOnce.doClick();
    }

    void clickRelease() {
        release.doClick();
    }

    void clickDescribe() {
        describe.doClick();
    }

    String hint() {
        return explanation;
    }
}
