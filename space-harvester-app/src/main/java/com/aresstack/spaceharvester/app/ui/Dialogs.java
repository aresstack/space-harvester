package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.PasswordReveal;
import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowCloseButton;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowDragger;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowResizer;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowShape;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import com.aresstack.spaceharvester.app.ui.brand.ApplicationIcons;
import com.aresstack.spaceharvester.app.ui.controls.HelpButton;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Modale Dialoge im Comic-Stil wie im Enterprise AI Client: rahmenlos mit eigener Kopfzeile und rundem ✕; dazu
 * Bestätigen und Geheimnis abfragen.
 */
public final class Dialogs {

    static final int WINDOW_PADDING = 4;
    static final int RESIZE_GRIP = 6;
    static final int CLOSE_SIZE = 24;
    static final String YES = "Ja";
    static final String NO = "Nein";

    private Dialogs() {
    }

    /**
     * Fragt eine riskante Aktion ab, mit „Nein“ und „Ja“ (rot); {@code true} nur nach „Ja“. Die Frage im Text sagt
     * „wirklich“ („Auftrag wirklich löschen?“), damit klar ist, dass es eine Vorsichtsabfrage ist (Angelo 10.11.2026).
     * Escape und ✕ heißen „Nein“.
     */
    public static boolean confirm(Component parent, String title, String question) {
        boolean[] result = {false};
        JDialog dialog = dialog(parent, title);
        ComposerButton yes = Comic.danger(YES, null);
        ComposerButton no = Comic.button(NO, null);
        yes.addActionListener(e -> {
            result[0] = true;
            dialog.dispose();
        });
        no.addActionListener(e -> dialog.dispose());
        show(dialog, title, Comic.note(question), no, yes);
        return result[0];
    }

    /**
     * Wie {@link #confirm} mit einem Ankreuzfeld {@code option} (anfangs leer) unter dem Text.
     *
     * @return {@code null} ohne Bestätigung, sonst ob das Feld angekreuzt war
     */
    public static Boolean confirm(Component parent, String title, String text, String option, String action) {
        Boolean[] result = {null};
        JDialog dialog = dialog(parent, title);
        javax.swing.JCheckBox box = Comic.checkBox(option);
        ComposerButton yes = Comic.danger(action, null);
        ComposerButton no = Comic.button("Abbrechen", null);
        yes.addActionListener(e -> {
            result[0] = box.isSelected();
            dialog.dispose();
        });
        no.addActionListener(e -> dialog.dispose());
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(Comic.note(text), BorderLayout.CENTER);
        body.add(box, BorderLayout.SOUTH);
        show(dialog, title, body, no, yes);
        return result[0];
    }

    /**
     * Vor jedem Übernehmen, das die Quellen neu startet: Laufen Aufträge, sagt der Dialog, welche abbrechen würden, und
     * übernimmt nur nach Bestätigung. Ohne laufende Aufträge {@code true} ohne Rückfrage.
     */
    static boolean restartAllowed(Component parent, AppContext context) {
        List<JobStatus> running = context.runningJobs();
        if (running.isEmpty()) {
            return true;
        }
        String jobs = running.stream().limit(5).map(job -> (job.type() == JobType.EXPORT ? "Export " : "Abgleich ")
                + job.sourceId() + (job.partitionKey() == null ? "" : " · " + job.partitionKey()))
                .collect(Collectors.joining(", ")) + (running.size() > 5 ? ", …" : "");
        return confirm(parent, "Laufende Aufträge", Texts.count(running.size(), "Auftrag läuft", "Aufträge laufen")
                + " noch: " + jobs + ". Zum Übernehmen startet die App die Quellen neu; "
                + (running.size() == 1 ? "dieser Auftrag bricht" : "diese Aufträge brechen") + " dabei ab. "
                + "Wer das nicht will, wartet, bis sie unter „Aufträge“ fertig sind, und übernimmt dann.\n\n"
                + "Wirklich übernehmen und " + (running.size() == 1 ? "den Auftrag" : "die Aufträge") + " abbrechen?");
    }

    /**
     * Fragt Passwort oder Token ab. Der Dialog speichert und zeigt den Wert nie; was damit geschieht, sagt der Text.
     *
     * @return das eingegebene Geheimnis (der Aufrufer löscht es nach Gebrauch) oder {@code null}
     */
    public static char[] askSecret(Component parent, String title, String text) {
        return askSecret(parent, title, text, "Übernehmen");
    }

    /** Wie {@link #askSecret(Component, String, String)} mit eigener Beschriftung des Bestätigungsknopfs. */
    public static char[] askSecret(Component parent, String title, String text, String accept) {
        char[][] result = {null};
        JDialog dialog = dialog(parent, title);
        JPasswordField field = new JPasswordField(28);
        Comic.style(field);
        PasswordReveal.install(field);
        ComposerButton ok = Comic.primary(accept, null);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ok.addActionListener(e -> {
            result[0] = field.getPassword();
            dialog.dispose();
        });
        field.addActionListener(e -> ok.doClick());
        cancel.addActionListener(e -> dialog.dispose());
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(Comic.note(text), BorderLayout.NORTH);
        body.add(field, BorderLayout.CENTER);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        show(dialog, title, body, cancel, ok);
        return result[0];
    }

    /**
     * Ein rahmenloses modales Comic-Fenster wie das Hauptfenster: keine Titelleiste des Systems, Schiff als Symbol,
     * Escape schließt. Den Inhalt setzt {@link #frame}.
     */
    static JDialog dialog(Component parent, String title) {
        Window owner = parent == null ? null : parent instanceof Window window ? window
                : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        ApplicationIcons.apply(dialog);
        return dialog;
    }

    /**
     * Setzt den Fensterinhalt: Tintenrand, Kopfzeile mit Überschrift und rundem ✕ (zum Verschieben ziehen), darunter
     * {@code body}. Escape und ✕ rufen {@code cancel}; der Rand lässt sich zum Vergrößern ziehen.
     */
    static JPanel frame(JDialog dialog, String title, JComponent body, Runnable cancel) {
        return frame(dialog, title, body, cancel, null);
    }

    /** Wie {@link #frame(JDialog, String, JComponent, Runnable)}, mit dem Fragezeichen links neben dem ✕. */
    static JPanel frame(JDialog dialog, String title, JComponent body, Runnable cancel, HelpButton help) {
        JPanel content = surface(title, body, cancel, help);
        dialog.setContentPane(content);
        return content;
    }

    /** Der Inhalt von {@link #frame} ohne Fenster (auch ohne Bildschirm prüfbar). */
    static JPanel surface(String title, JComponent body, Runnable cancel) {
        return surface(title, body, cancel, null);
    }

    /** Wie {@link #surface(String, JComponent, Runnable)}; {@code help} steht links neben dem ✕, {@code null} entfällt. */
    static JPanel surface(String title, JComponent body, Runnable cancel, HelpButton help) {
        // Der Tintenrand gehört allein der äußeren Fläche: ComicWindowShape ersetzt ihn beim Anzeigen (rund oder
        // eckig). Der Innenabstand sitzt deshalb auf einer eigenen Fläche darin und bleibt erhalten.
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Comic.SURFACE);
        content.setBorder(ComicBorder.windowBorder(Comic.PALETTE, WINDOW_PADDING));
        JPanel padded = new JPanel(new BorderLayout(0, 10));
        padded.setOpaque(false);
        padded.setBorder(BorderFactory.createEmptyBorder(8, 18, 14, 12));
        content.add(padded, BorderLayout.CENTER);
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JLabel heading = new JLabel(title);
        heading.setFont(Comic.semiBold(16f));
        heading.setForeground(Comic.INK);
        header.add(heading, BorderLayout.CENTER);
        JPanel chips = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        chips.setOpaque(false);
        if (help != null) {
            chips.add(closeButton(help));
        }
        chips.add(closeButton(new ComicWindowCloseButton(Comic.PALETTE, cancel, "Schließen (Esc)", CLOSE_SIZE)));
        header.add(chips, BorderLayout.EAST);
        ComicWindowDragger.install(header);
        JPanel inner = new JPanel(new BorderLayout());
        inner.setOpaque(false);
        inner.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        inner.add(body, BorderLayout.CENTER);
        padded.add(header, BorderLayout.NORTH);
        padded.add(inner, BorderLayout.CENTER);
        content.registerKeyboardAction(e -> cancel.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        ComicWindowResizer.install(content, RESIZE_GRIP);
        return content;
    }

    /**
     * Hält ein ✕ in seiner quadratischen Größe, mittig in der Höhe. Direkt in einem {@code BorderLayout} würde es auf
     * die Höhe der Kopfzeile gestreckt, und der Ring würde zum Ei.
     */
    static JPanel closeButton(JComponent button) {
        JPanel holder = new JPanel(new GridBagLayout());
        holder.setOpaque(false);
        holder.add(button);
        return holder;
    }

    /** Packt, rundet die Ecken, zentriert über dem Besitzer und zeigt den Dialog modal. */
    static void present(JDialog dialog, Dimension minimum) {
        present(dialog, minimum, null, size -> { });
    }

    /** Wie {@link #present(JDialog, Dimension)}, mit fester Wunschgröße statt der gepackten; {@code sized} erfährt die Größe beim Öffnen. */
    static void present(JDialog dialog, Dimension minimum, Dimension preferred,
                        java.util.function.Consumer<Dimension> sized) {
        dialog.pack();
        // Nie höher oder breiter als der nutzbare Bildschirm (ohne Taskleiste): Sonst ragt die Knopfleiste unten
        // heraus, und der Bildlauf im Inhalt glaubt, alles passe, bis eine Meldung Platz nimmt (Angelo, 10.11.).
        java.awt.Rectangle screen = usableScreen(dialog);
        Dimension fitted = fit(preferred != null ? preferred : dialog.getSize(), minimum, screen);
        if (minimum != null) {
            dialog.setMinimumSize(fit(minimum, null, screen));
        }
        dialog.setSize(fitted);
        ComicWindowShape.install(dialog, (JComponent) dialog.getContentPane(), Comic.PALETTE, WINDOW_PADDING,
                ResearchUiMetrics.RADIUS_WINDOW);
        dialog.setLocationRelativeTo(dialog.getOwner());
        if (screen != null) {
            // Ganz auf dem Bildschirm, auch wenn der Besitzer nah am Rand steht.
            int x = Math.max(screen.x, Math.min(dialog.getX(), screen.x + screen.width - dialog.getWidth()));
            int y = Math.max(screen.y, Math.min(dialog.getY(), screen.y + screen.height - dialog.getHeight()));
            dialog.setLocation(x, y);
        }
        sized.accept(dialog.getSize());
        dialog.setVisible(true);
    }

    /**
     * Fenstergröße: mindestens {@code minimum}, höchstens der nutzbare Bildschirm {@code screen} ({@code null}: keine
     * Grenze, etwa ohne Bildschirm). Die Grenze des Bildschirms gewinnt über das Minimum.
     */
    static Dimension fit(Dimension size, Dimension minimum, java.awt.Rectangle screen) {
        int width = minimum == null ? size.width : Math.max(size.width, minimum.width);
        int height = minimum == null ? size.height : Math.max(size.height, minimum.height);
        if (screen != null) {
            width = Math.min(width, screen.width);
            height = Math.min(height, screen.height);
        }
        return new Dimension(width, height);
    }

    /** Der nutzbare Bereich des Bildschirms, auf dem das Fenster (bzw. sein Besitzer) steht; ohne Bildschirm {@code null}. */
    static java.awt.Rectangle usableScreen(Window window) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return null;
        }
        Window anchor = window.getOwner() != null && window.getOwner().isShowing() ? window.getOwner() : window;
        java.awt.GraphicsConfiguration config = anchor.getGraphicsConfiguration();
        if (config == null) {
            return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        }
        java.awt.Rectangle bounds = config.getBounds();
        java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config);
        return new java.awt.Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
    }

    /** Zeigt {@code body} in einem rahmenlosen Fenster ohne Knöpfe; Escape und ✕ schließen (z. B. ein vergrößertes Bild). */
    public static void view(Component parent, String title, JComponent body) {
        JDialog dialog = dialog(parent, title);
        frame(dialog, title, body, dialog::dispose);
        present(dialog, new Dimension(320, 240));
    }

    /**
     * Wie {@link #view}, aber das Fenster merkt sich unter {@code sizeKey} die Größe, die der Benutzer zuletzt gezogen
     * hat; ohne gemerkte Größe ist es {@code share} des nutzbaren Bildschirms groß. Beim Öffnen wird die Größe immer
     * auf den aktuellen Bildschirm begrenzt (sonst läge das ✕ nach einem Bildschirmwechsel außerhalb); gespeichert wird
     * nur eine selbst gezogene Größe, nicht diese Begrenzung.
     */
    public static void viewSized(Component parent, String title, JComponent body, String sizeKey, double share) {
        JDialog dialog = dialog(parent, title);
        Dimension[] opened = new Dimension[1];
        Runnable close = () -> {
            if (opened[0] != null && !opened[0].equals(dialog.getSize())) {
                WindowSizes.remember(sizeKey, dialog.getSize());
            }
            dialog.dispose();
        };
        frame(dialog, title, body, close);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                close.run();
            }
        });
        java.awt.Rectangle screen = usableScreen(dialog);
        Dimension preferred = WindowSizes.recall(sizeKey).orElseGet(() -> screen == null ? new Dimension(900, 700)
                : new Dimension((int) (screen.width * share), (int) (screen.height * share)));
        present(dialog, new Dimension(320, 240), preferred, size -> opened[0] = size);
    }

    static void show(JDialog dialog, String title, JComponent body, ComposerButton... buttons) {
        JPanel stack = new JPanel(new BorderLayout(0, 12));
        stack.setOpaque(false);
        stack.add(body, BorderLayout.CENTER);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);
        for (ComposerButton button : buttons) {
            row.add(button);
        }
        stack.add(row, BorderLayout.SOUTH);
        frame(dialog, title, stack, dialog::dispose);
        dialog.getRootPane().setDefaultButton(buttons[buttons.length - 1]);
        dialog.pack();
        present(dialog, new Dimension(460, dialog.getHeight()));
    }
}
