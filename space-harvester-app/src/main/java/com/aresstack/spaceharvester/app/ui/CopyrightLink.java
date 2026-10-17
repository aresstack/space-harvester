package com.aresstack.spaceharvester.app.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;

/**
 * „© 2026 AresStack“ in der Statuszeile wie im Enterprise AI Client: grau; der Name wird beim Darüberfahren zum
 * blauen, unterstrichenen Link mit Hand-Cursor und öffnet die Release-Übersicht im Standardbrowser.
 */
final class CopyrightLink extends JPanel {

    static final String RELEASES = "https://github.com/aresstack/space-harvester/releases";

    private final JLabel link = new JLabel("AresStack");

    CopyrightLink() {
        this(CopyrightLink::browse);
    }

    CopyrightLink(Consumer<URI> opener) {
        super(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        setOpaque(false);
        Font plain = Comic.regular(12f);
        Font underlined = plain.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
        JLabel copyright = new JLabel("© 2026 ");
        copyright.setFont(plain);
        copyright.setForeground(Comic.MUTED);
        link.setFont(plain);
        link.setForeground(Comic.MUTED);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(RELEASES);
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                opener.accept(URI.create(RELEASES));
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                link.setFont(underlined);
                link.setForeground(Comic.BLUE);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                link.setFont(plain);
                link.setForeground(Comic.MUTED);
            }
        });
        add(copyright);
        add(link);
    }

    JLabel link() {
        return link;
    }

    private static void browse(URI target) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(target);
            }
        } catch (Exception ignored) {
            // Ohne Browser bleibt der Name nur Text; die Adresse steht im Tooltip.
        }
    }
}
