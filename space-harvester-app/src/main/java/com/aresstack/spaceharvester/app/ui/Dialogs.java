package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowCloseButton;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowDragger;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowResizer;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowShape;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import com.aresstack.spaceharvester.app.ui.brand.ApplicationIcons;
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

    private Dialogs() {
    }

    /** Fragt eine riskante Aktion ab; {@code true} nur nach ausdrücklicher Bestätigung. */
    public static boolean confirm(Component parent, String title, String text, String action) {
        boolean[] result = {false};
        JDialog dialog = dialog(parent, title);
        ComposerButton yes = Comic.danger(action, null);
        ComposerButton no = Comic.button("Abbrechen", null);
        yes.addActionListener(e -> {
            result[0] = true;
            dialog.dispose();
        });
        no.addActionListener(e -> dialog.dispose());
        show(dialog, title, Comic.note(text), no, yes);
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
                + "Wer das nicht will, wartet, bis sie unter „Aufträge“ fertig sind, und übernimmt dann.",
                "Trotzdem übernehmen");
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
        JPanel content = surface(title, body, cancel);
        dialog.setContentPane(content);
        return content;
    }

    /** Der Inhalt von {@link #frame} ohne Fenster (auch ohne Bildschirm prüfbar). */
    static JPanel surface(String title, JComponent body, Runnable cancel) {
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
        header.add(closeButton(new ComicWindowCloseButton(Comic.PALETTE, cancel, "Schließen (Esc)", CLOSE_SIZE)),
                BorderLayout.EAST);
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
        dialog.pack();
        if (minimum != null) {
            dialog.setMinimumSize(minimum);
            dialog.setSize(Math.max(dialog.getWidth(), minimum.width), Math.max(dialog.getHeight(), minimum.height));
        }
        ComicWindowShape.install(dialog, (JComponent) dialog.getContentPane(), Comic.PALETTE, WINDOW_PADDING,
                ResearchUiMetrics.RADIUS_WINDOW);
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    /** Zeigt {@code body} in einem rahmenlosen Fenster ohne Knöpfe; Escape und ✕ schließen (z. B. ein vergrößertes Bild). */
    public static void view(Component parent, String title, JComponent body) {
        JDialog dialog = dialog(parent, title);
        frame(dialog, title, body, dialog::dispose);
        present(dialog, new Dimension(320, 240));
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
