package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.standing.UnknownStandingTargetException;
import java.awt.BorderLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Bildschirmfotos des Overlays „Belege“ mit ✓/✕ (W3) zur Sichtprüfung, je bei 960 und 900 px Fensterbreite: Zeile
 * ungesetzt, bestätigt, verworfen, gesperrt (ohne Recht), sehr langer Seitentitel und Fehlerfall (rote Meldung).
 * Erfundene Daten (Quelle „wiki“, Space „DEV“). Läuft nur mit der Umgebungsvariable {@code SCREENSHOTS_DIR}, allein
 * gestartet und mit Bildschirm (etwa {@code xvfb-run -a -s "-screen 0 3000x2000x24"}).
 */
class EvidencePopupScreenshotTest {

    private static final PageKey PLAN = new PageKey("wiki", "1");
    private static final PageKey ZIEL = new PageKey("wiki", "2");
    private static final PageKey NACHTLAUF = new PageKey("wiki", "3");
    private static final String LONG_TITLE = "Plan für den Nachtlauf mit Start, Ziel und allen Abhängigkeiten der "
            + "Sicherung im Rechenzentrum Nord";

    @Test
    void renderEvidenceReview() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        System.setProperty("java.awt.headless", "false");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("sun.java2d.uiScale", "2");
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "braucht einen Bildschirm (Xvfb)");
        Path out = Path.of(directory);
        Files.createDirectories(out);
        for (int width : new int[] {960, 900}) {
            shot(width, out, "ungesetzt", core -> { }, popup -> { }, false);
            shot(width, out, "bestaetigt", core -> { }, popup -> popup.toggle(0).clickConfirm(), false);
            shot(width, out, "verworfen", core -> { }, popup -> popup.toggle(0).clickReject(), false);
            shot(width, out, "gesperrt", core -> core.readOnly.addAll(List.of(PLAN, ZIEL, NACHTLAUF)),
                    popup -> { }, false);
            shot(width, out, "langer-titel", core -> { }, popup -> { }, true);
            shot(width, out, "fehler", core -> core.refuse = new UnknownStandingTargetException("weg"),
                    popup -> popup.toggle(0).clickConfirm(), false);
        }
    }

    private void shot(int width, Path out, String name, Consumer<EvidencePopupTest.Core> prepare,
                      Consumer<EvidencePopup> act, boolean longTitle) throws Exception {
        EvidencePopupTest.Core core = new EvidencePopupTest.Core();
        prepare.accept(core);
        JFrame[] frame = new JFrame[1];
        EvidencePopup[] popup = new EvidencePopup[1];
        JPanel[] root = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            root[0] = new JPanel(new BorderLayout());
            root[0].setBackground(Comic.SURFACE);
            frame[0] = new JFrame("Space Harvester");
            frame[0].setUndecorated(true);
            frame[0].setContentPane(root[0]);
            frame[0].setSize(width, 600);
            frame[0].setLocation(0, 0);
            frame[0].setVisible(true);
            popup[0] = new EvidencePopup().reviewing(core, new EvidencePopup.Runner() {
                @Override
                public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                    T result;
                    try {
                        result = work.call();
                    } catch (Exception e) {
                        onFailure.accept(e);
                        return;
                    }
                    onSuccess.accept(result);
                }
            });
            List<DomainTermGraph.Evidence> evidence = List.of(
                    EvidencePopupTest.evidence(PLAN, longTitle ? LONG_TITLE : "Plan", false),
                    EvidencePopupTest.checkedEvidence(ZIEL, "Ziel"),
                    EvidencePopupTest.evidence(NACHTLAUF, "Nachtlauf", false));
            popup[0].fill("Plan startet Nachtlauf", 3, evidence, EvidencePopupTest.sentences(), false)
                    .showAt(root[0], width - 520, 40);
        });
        settle();
        SwingUtilities.invokeAndWait(() -> act.accept(popup[0]));
        settle();
        SwingUtilities.invokeAndWait(() -> write(frame[0].getRootPane(),
                out.resolve("w3-beleg-" + name + "-" + width + ".png")));
        SwingUtilities.invokeAndWait(() -> {
            popup[0].setVisible(false);
            frame[0].dispose();
        });
        settle();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(150);
        }
    }

    /** Doppelt aufgelöst; das Overlay liegt als leichtes Popup in der Fensterebene und wird mitgemalt. */
    private static void write(JComponent component, Path file) {
        try {
            component.validate();
            BufferedImage image = new BufferedImage(component.getWidth() * 2, component.getHeight() * 2,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(2, 2);
            g.setColor(Comic.SURFACE);
            g.fillRect(0, 0, component.getWidth(), component.getHeight());
            component.paint(g);
            g.dispose();
            ImageIO.write(image, "png", file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
