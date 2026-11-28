package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.ui.controls.CopyButton;
import com.aresstack.spaceharvester.app.ui.controls.ViewerWheel;
import com.aresstack.spaceharvester.application.sources.CertificateCode;
import com.aresstack.spaceharvester.source.network.QrCodes;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * QR-Codes am Bildschirm, zum Abscannen mit dem Telefon: die vertrauten Zertifikate einer Quelle (Strang ZX) oder eine
 * Chat-Nachricht, die nicht in einen Code passt, in mehreren Teilen. Ein Code groß und mittig auf weißem Feld mit
 * Ruhezone, ◀ ▶ links und rechts, Mausrad und Pfeiltasten blättern; darunter „2 von 5“, Name, Rolle und eine Angabe mit
 * Kopierknopf (z. B. SHA-1). Schließen nur über den Esc-Kreis.
 */
public final class QrCodesView {

    static final String TITLE = "Zertifikate als QR-Code";
    static final String TOO_LARGE = "passt nicht in einen Code; bitte das Archiv nutzen";
    public static final String MESSAGE_TITLE = "Nachricht als QR-Code";

    /** Ein Code: Name und Rolle als Beschriftung, {@code detail} mit Kopierknopf für {@code copy} (leer = keine Zeile). */
    public record Code(String name, String role, String detail, String copy, BufferedImage image) {
    }

    /** Die Texte einer Ansicht: leere Liste, Blättern-Tooltips und Hinweis bei einem fehlenden Code. */
    record Texts(String empty, String previous, String next, String tooLarge) {
        static final Texts CERTIFICATES = new Texts("Keine vertrauten Zertifikate", "Voriges Zertifikat (←)",
                "Nächstes Zertifikat (→)", TOO_LARGE);
        static final Texts MESSAGE = new Texts("Leere Nachricht", "Voriger Teil (←)", "Nächster Teil (→)",
                "passt nicht in einen Code");
    }

    private final List<Code> codes;
    private final Texts texts;
    private final JPanel body = new JPanel(new BorderLayout(8, 8));
    private final CodePanel code = new CodePanel();
    private final IconButton previous;
    private final IconButton next;
    private final JLabel position = new JLabel("", SwingConstants.CENTER);
    private final JLabel name = new JLabel("", SwingConstants.CENTER);
    private final JLabel role = new JLabel("", SwingConstants.CENTER);
    /** „passt nicht in einen Code“, gelb wie „bewusst ausgelassen“ (UX-Manager); sonst unsichtbar. */
    private final JLabel tooLarge;
    private final JLabel sha1 = new JLabel();
    private final JPanel fingerprint = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private int index;

    /** Die Zertifikate einer Quelle (Strang ZX). */
    QrCodesView(List<CertificateCode> codes) {
        this(Texts.CERTIFICATES, Objects.requireNonNull(codes, "codes").stream()
                .map(c -> new Code(c.name(), c.role(), "SHA-1 " + c.sha1(), c.sha1(), c.image())).toList());
    }

    QrCodesView(Texts texts, List<Code> codes) {
        this.texts = Objects.requireNonNull(texts, "texts");
        this.codes = List.copyOf(Objects.requireNonNull(codes, "codes"));
        previous = new IconButton(ComposerIcons.chevronLeft(), texts.previous());
        next = new IconButton(ComposerIcons.chevronRight(), texts.next());
        tooLarge = new JLabel(texts.tooLarge(), SwingConstants.CENTER);
        body.setOpaque(false);
        previous.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(1));
        body.add(previous, BorderLayout.WEST);
        body.add(code, BorderLayout.CENTER);
        body.add(next, BorderLayout.EAST);

        name.setFont(Comic.semiBold(14f));
        name.setForeground(Comic.INK);
        role.setForeground(Comic.MUTED);
        position.setForeground(Comic.MUTED);
        sha1.setForeground(Comic.MUTED);
        sha1.setFont(Comic.regular(12f));
        fingerprint.setOpaque(false);
        fingerprint.add(sha1);
        fingerprint.add(new CopyButton(() -> codes.isEmpty() ? "" : current().copy()));
        JPanel caption = new JPanel();
        caption.setOpaque(false);
        caption.setLayout(new javax.swing.BoxLayout(caption, javax.swing.BoxLayout.Y_AXIS));
        tooLarge.setOpaque(true);
        tooLarge.setBackground(Comic.YELLOW);
        tooLarge.setForeground(Comic.INK);
        tooLarge.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        tooLarge.setVisible(false);
        for (JComponent line : List.of(position, name, role, tooLarge, fingerprint)) {
            line.setAlignmentX(Component.CENTER_ALIGNMENT);
            caption.add(line);
        }
        body.add(caption, BorderLayout.SOUTH);

        code.addMouseWheelListener(new ViewerWheel(new ViewerWheel.Target() {
            @Override
            public boolean pan(int dx, int dy) {
                return false;
            }

            @Override
            public void zoom(double factor, Point at) {
            }

            @Override
            public void previous() {
                step(-1);
            }

            @Override
            public void next() {
                step(1);
            }
        }));
        bind("LEFT", KeyEvent.VK_LEFT, -1);
        bind("RIGHT", KeyEvent.VK_RIGHT, 1);
        show();
    }

    /** Öffnet die Zertifikate einer Quelle als Overlay über {@code parent}. */
    static void open(Component parent, String source, List<CertificateCode> codes) {
        present(parent, TITLE + " · " + source, new QrCodesView(codes));
    }

    /**
     * Öffnet eine Nachricht als QR-Code über {@code parent}; passt sie nicht in einen Code, in mehreren Teilen zum
     * Blättern, nie gekürzt. Ein Telefon liest die Teile nacheinander und hängt sie aneinander.
     */
    public static void openText(Component parent, String name, String text) {
        present(parent, MESSAGE_TITLE + " · " + name, forText(name, text));
    }

    /** Die Ansicht einer Nachricht, für Tests ohne Fenster. */
    static QrCodesView forText(String name, String text) {
        List<BufferedImage> images = QrCodes.textImages(text, 1);
        List<Code> codes = new java.util.ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            String role = images.size() > 1 ? "Teil " + (i + 1) + " von " + images.size() : "";
            codes.add(new Code(name, role, "", "", images.get(i)));
        }
        return new QrCodesView(Texts.MESSAGE, codes);
    }

    private static void present(Component parent, String title, QrCodesView view) {
        Window owner = parent == null ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = Dialogs.dialog(owner, title);
        Dialogs.escFrame(dialog, title, view.component(), dialog::dispose);
        Dialogs.present(dialog, new Dimension(560, 0));
    }

    int size() {
        return codes.size();
    }

    BufferedImage image() {
        return codes.isEmpty() ? null : current().image();
    }

    JComponent component() {
        return body;
    }

    boolean tooLargeShown() {
        return tooLarge.isVisible();
    }

    int index() {
        return index;
    }

    String positionText() {
        return position.getText();
    }

    void step(int delta) {
        if (codes.isEmpty()) {
            return;
        }
        index = Math.floorMod(index + delta, codes.size());
        show();
    }

    private Code current() {
        return codes.get(index);
    }

    private void show() {
        boolean several = codes.size() > 1;
        previous.setEnabled(several);
        next.setEnabled(several);
        if (codes.isEmpty()) {
            position.setText("");
            name.setText(texts.empty());
            tooLarge.setVisible(false);
            role.setText("");
            sha1.setText("");
            fingerprint.setVisible(false);
            code.image = null;
            code.repaint();
            return;
        }
        Code shown = current();
        position.setText((index + 1) + " von " + codes.size());
        name.setText(shown.name());
        role.setText(shown.role());
        tooLarge.setVisible(shown.image() == null);
        sha1.setText(shown.detail());
        fingerprint.setVisible(!shown.detail().isEmpty());
        code.image = shown.image();
        code.repaint();
    }

    private void bind(String key, int keyCode, int delta) {
        body.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), key);
        body.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                step(delta);
            }
        });
    }

    /**
     * Ganzzahlige Pixel je Modul (das Bild hat ein Pixel je Modul), so groß wie das Feld erlaubt, mindestens 1: nie
     * unscharf zwischen zwei Größen skaliert.
     */
    static int scale(int width, int height, int imageWidth, int imageHeight) {
        return Math.max(1, Math.min(width / imageWidth, height / imageHeight));
    }

    /** Das Bild des Codes, wie es gezeichnet wird, für Tests. */
    static BufferedImage paint(BufferedImage code, int size) {
        CodePanel panel = new CodePanel();
        panel.image = code;
        panel.setSize(size, size);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        // Wie ein Bildschirm mit Glättung: das Feld muss sie für den Code selbst abschalten.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        panel.paintComponent(g);
        g.dispose();
        return out;
    }

    /** Weißes Feld; der Code in ganzzahliger Vergrößerung ohne Glättung, damit die Module scharf bleiben. */
    private static final class CodePanel extends JComponent {

        private static final int SIZE = 420;
        BufferedImage image;

        CodePanel() {
            setBorder(BorderFactory.createLineBorder(Comic.LINE));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(SIZE, SIZE);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(AppColors.of("app.QrCodesView.quietZone"));
                g.fillRect(0, 0, getWidth(), getHeight());
                if (image == null) {
                    return;
                }
                int scale = scale(getWidth(), getHeight(), image.getWidth(), image.getHeight());
                int w = image.getWidth() * scale;
                int h = image.getHeight() * scale;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            } finally {
                g.dispose();
            }
        }
    }
}
