package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.PerspectiveSwitcher;
import com.aresstack.spaceharvester.app.ui.controls.ShipArchive;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Avenger-Easter-Egg (#1141), eine Hommage an XCOM 2 in eigener Gestaltung: Die Oberfläche flackert kurz, unter dem
 * Schriftzug „Space Harvester“ schimmert die alte Kennung „AVENGER“ durch, dann öffnet sich das Bordarchiv mit der
 * Vorgeschichte des Schiffs. Nichts daran ändert Perspektive, Ansicht oder Sitzungen. Der Ablauf kennt keinen
 * Auslöser: Wer ihn starten will, ruft {@link #reveal()} (vorläufig fünf Klicks auf das Logo, siehe MainWindow).
 */
public final class AvengerEasterEgg {

    static final String OLD_NAME = "AVENGER";
    static final String HEADING = "BORDARCHIV · DATENSATZ WIEDERHERGESTELLT";
    static final List<String> RECORD = List.of(
            "Ursprüngliche Schiffskennung: AVENGER",
            "Herkunft: erbeuteter Transporter der Besatzer, vom Widerstand XCOM zur fliegenden Kommandozentrale umgebaut.",
            "Verbleib: Nach der Befreiung der Erde restauriert und für die interstellare Ressourcensuche umgerüstet.",
            "Neue Kennung: SPACE HARVESTER",
            "Auftrag: Ressourcen, Technologien und Wissen für den Wiederaufbau der Erde finden.",
            "Räume an Bord: Command Deck steuert die Systeme · Mission Control plant die Einsätze · Proving Ground"
                    + " erprobt Technologien · Space Harvester sammelt und erschließt Wissen.",
            "Hinweis der Werft: Unter der neuen Lackierung ist die alte Kennung noch zu erkennen.");
    private static final int FLICKER_MS = 650;
    private static final int ARCHIVE_DELAY_MS = 1600;

    private final PerspectiveSwitcher switcher;
    private boolean running;

    public AvengerEasterEgg(PerspectiveSwitcher switcher) {
        this.switcher = switcher;
    }

    /** Spielt den Ablauf: Flackern, alte Kennung im Titel, dann das Bordarchiv. */
    public void reveal() {
        JRootPane root = SwingUtilities.getRootPane(switcher);
        // Läuft schon ein Ablauf (Flackern, Warten aufs Archiv), zählt ein weiterer Auslöser nicht: sonst öffnete
        // sich das Archiv nach dem Schließen ein zweites Mal.
        if (root == null || running) {
            return;
        }
        running = true;
        // Erst flackert es, dann schimmert die alte Kennung durch, und erst wenn man sie sehen konnte, meldet sich
        // das Bordarchiv.
        switcher.showHeritage(OLD_NAME, Duration.ofSeconds(9));
        flicker(root.getLayeredPane(), () -> {
            Timer archive = new Timer(ARCHIVE_DELAY_MS, e -> {
                running = false;
                ShipArchive.open(switcher, HEADING, RECORD);
            });
            archive.setRepeats(false);
            archive.start();
        });
    }

    /** Öffnet nur das Archiv, ohne Effekte (für Tests und andere Zugänge). */
    public ShipArchive openArchive() {
        return ShipArchive.open(switcher, HEADING, RECORD);
    }

    /** Ein kurzes, unruhiges Abdunkeln des ganzen Fensters wie bei einer schwankenden Bordspannung. */
    private static void flicker(JLayeredPane layers, Runnable then) {
        Random random = new Random();
        float[] alpha = {0f};
        JComponent veil = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(new Color(6, 12, 24, Math.round(alpha[0] * 255)));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        veil.setBounds(0, 0, layers.getWidth(), layers.getHeight());
        layers.add(veil, JLayeredPane.DRAG_LAYER);
        long start = System.currentTimeMillis();
        Timer timer = new Timer(45, null);
        timer.addActionListener(e -> {
            long t = System.currentTimeMillis() - start;
            if (t >= FLICKER_MS) {
                timer.stop();
                remove(layers, veil);
                then.run();
                return;
            }
            alpha[0] = random.nextFloat() < 0.5f ? 0.15f + random.nextFloat() * 0.45f : 0f;
            veil.repaint();
        });
        timer.start();
    }

    private static void remove(JLayeredPane layers, Component veil) {
        layers.remove(veil);
        layers.repaint();
    }
}
