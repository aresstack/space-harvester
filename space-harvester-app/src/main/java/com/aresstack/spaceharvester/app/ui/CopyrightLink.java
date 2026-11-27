package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.project.Project;
import com.aresstack.spaceharvester.app.ui.controls.Cursors;
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
import java.time.Year;
import java.util.Map;
import java.util.function.Consumer;

/**
 * „© ‹Jahr› AresStack“ in der Statuszeile wie im Enterprise AI Client: grau; der Name wird beim Darüberfahren zum
 * blauen, unterstrichenen Link mit Hand-Cursor. Das Jahr ist immer das aktuelle (Angelo, 27.11.2026).
 *
 * <p>Zugleich die Wahl des aktiven Projekts (ADR 0043, Nachtrag F6): Rechts daneben ein kleiner Pfeil nach oben, der die
 * Projektliste öffnet. Im Standardprojekt steht „AresStack“ und führt zur Release-Übersicht; in jedem anderen Projekt
 * steht dessen Name, und der Klick öffnet das Ziel des Projekts. Ein Projekt hat keine Farbe (F1).
 */
final class CopyrightLink extends JPanel {

    static final String RELEASES = "https://github.com/aresstack/space-harvester/releases";
    static final String CHOOSE_TIP = "Projekt wählen";

    private final JLabel copyright = new JLabel();
    private final JLabel link = new JLabel(Project.DEFAULT_NAME);
    private final IconButton choose = new IconButton(ComposerIcons.chevronUp(), CHOOSE_TIP).compact();
    private final Consumer<URI> opener;
    private Project project = Project.standard();
    private Consumer<Project> openProject = project -> { };

    CopyrightLink() {
        this(CopyrightLink::browse);
    }

    CopyrightLink(Consumer<URI> opener) {
        super(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        this.opener = opener;
        setOpaque(false);
        Font plain = Comic.regular(12f);
        Font underlined = plain.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
        copyright.setText("© " + Year.now().getValue() + " ");
        copyright.setFont(plain);
        copyright.setForeground(Comic.MUTED);
        link.setFont(plain);
        link.setForeground(Comic.MUTED);
        Cursors.set(link, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(RELEASES);
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (project.isDefault()) {
                    opener.accept(URI.create(RELEASES));
                } else {
                    openProject.accept(project);
                }
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
        choose.getAccessibleContext().setAccessibleName(CHOOSE_TIP);
        choose.setVisible(false);
        add(copyright);
        add(link);
        add(choose);
    }

    /**
     * Macht die Ecke zur Projektwahl: {@code choose} öffnet die Projektliste über dem Pfeil, {@code openProject} das
     * Ziel eines Projekts (nicht des Standardprojekts).
     */
    void chooser(Consumer<IconButton> choose, Consumer<Project> openProject) {
        this.choose.addActionListener(e -> choose.accept(this.choose));
        this.choose.setVisible(true);
        this.openProject = openProject;
    }

    /** Zeigt das aktive Projekt. */
    void show(Project active) {
        project = active == null ? Project.standard() : active;
        link.setText(project.name());
        link.setToolTipText(project.isDefault() ? RELEASES
                : project.target() == null ? "Ziel des Projekts festlegen" : project.name() + ": Ziel öffnen");
        revalidate();
        repaint();
    }

    JLabel link() {
        return link;
    }

    JLabel copyright() {
        return copyright;
    }

    IconButton chooseButton() {
        return choose;
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
