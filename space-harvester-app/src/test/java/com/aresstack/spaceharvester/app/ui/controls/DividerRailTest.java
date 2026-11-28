package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.dnd.DragSource;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Angelo 28.11.2026: Die senkrechte Knopfleiste einer Seitenleiste ist der Griff ihres Trenners. Ziehen an ihr
 * verschiebt den Trenner, ein Klick schaltet um; ohne Trenner steht sie am Fensterrand.
 */
class DividerRailTest {

    private static final Icon ICON = new Icon() {
        @Override
        public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
            g.fillRect(x + 4, y + 4, 8, 8);
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    };

    private static void layoutAll(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    private static void mouse(Component target, int id, int screenX) {
        int mask = id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
        target.dispatchEvent(new MouseEvent(target, id, 0, mask, 4, 4, screenX, 300, 1, false, MouseEvent.BUTTON1));
    }

    @Test
    void theRailIsTheGripOfItsDividerDragsItAndStillSwitchesOnAClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EdgeOverlay overlay = new EdgeOverlay();
            JPanel bar = new JPanel();
            bar.setPreferredSize(new java.awt.Dimension(10, 30));
            overlay.add(bar, BorderLayout.NORTH);
            overlay.bar(bar);
            JPanel main = new JPanel();
            JPanel side = new JPanel();
            main.setMinimumSize(new java.awt.Dimension(50, 0));
            side.setMinimumSize(new java.awt.Dimension(50, 0));
            JSplitPane pane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, main, side);
            ComicDivider.install(pane, Comic.PALETTE);
            overlay.add(pane, BorderLayout.CENTER);
            List<String> events = new ArrayList<>();
            SegmentedToggle rail = new SegmentedToggle().segment("Chat", ICON).segment("Aufträge", ICON);
            rail.onSelect(name -> events.add("wahl " + name));
            rail.onAgain(name -> events.add("nochmal " + name));
            rail.vertical(true);
            assertThat(ComicDivider.grip(pane)).as("ohne Knopfleiste: gelber Griff").isTrue();

            DividerRail grip = DividerRail.install(pane, rail, overlay, overlay.right());
            overlay.place(overlay.right(), rail);
            overlay.setSize(800, 600);
            layoutAll(overlay);
            pane.setDividerLocation(500);
            layoutAll(overlay);
            layoutAll(overlay);

            assertThat(ComicDivider.grip(pane)).as("die Knopfleiste ersetzt den gelben Griff").isFalse();
            assertThat(grip.onDivider()).isTrue();
            Rectangle slot = overlay.right().getBounds();
            int center = 500 + ComicDivider.THICKNESS / 2;
            assertThat(slot.x + slot.width / 2).as("mittig auf der Linie des Trenners").isEqualTo(center);
            // An der Stelle des gelben Griffs: in der Mitte der Länge des Trenners, nicht oben.
            java.awt.Component divider = ((javax.swing.plaf.basic.BasicSplitPaneUI) pane.getUI()).getDivider();
            java.awt.Point middle = SwingUtilities.convertPoint(divider.getParent(), divider.getX(),
                    divider.getY() + divider.getHeight() / 2, overlay);
            assertThat(slot.y + slot.height / 2).as("senkrecht mittig, wo der Griff war").isEqualTo(middle.y);
            // Die Linie läuft oben und unten weiter; hinter der Knopfleiste ist sie unterbrochen.
            assertThat(ComicDivider.gap(pane)).isEqualTo(rail.getPreferredSize().height);
            java.awt.image.BufferedImage line = new java.awt.image.BufferedImage(divider.getWidth(),
                    divider.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D lg = line.createGraphics();
            divider.paint(lg);
            lg.dispose();
            int ink = Comic.INK.getRGB();
            int lineX = divider.getWidth() / 2 - 1;
            assertThat(line.getRGB(lineX, 20)).as("Linie oben").isEqualTo(ink);
            assertThat(line.getRGB(lineX, divider.getHeight() / 2)).as("keine Linie hinter der Knopfleiste")
                    .isNotEqualTo(ink);
            int half = rail.getPreferredSize().height / 2;
            assertThat(line.getRGB(lineX, divider.getHeight() / 2 - half - 3)).as("Linie bis an die Leiste")
                    .isEqualTo(ink);
            assertThat(line.getRGB(lineX, divider.getHeight() / 2 + half + 3)).isEqualTo(ink);
            assertThat(line.getRGB(lineX, divider.getHeight() - 20)).as("Linie unten").isEqualTo(ink);

            // Ziehen über die Schwelle hinaus verschiebt den Trenner und löst den Knopf nicht aus.
            JToggleButton jobs = rail.button("Aufträge");
            int far = DragSource.getDragThreshold() + 60;
            mouse(jobs, MouseEvent.MOUSE_PRESSED, 1000);
            mouse(jobs, MouseEvent.MOUSE_DRAGGED, 1000 - far);
            mouse(jobs, MouseEvent.MOUSE_RELEASED, 1000 - far);
            assertThat(pane.getDividerLocation()).isEqualTo(500 - far);
            assertThat(events).as("Ziehen ist kein Klick").isEmpty();
            assertThat(rail.selected()).isEqualTo("Chat");

            // Wie an der Linie endet das Ziehen an der kleinsten Breite der Seiten.
            int placed = pane.getDividerLocation();
            mouse(jobs, MouseEvent.MOUSE_PRESSED, 1000);
            mouse(jobs, MouseEvent.MOUSE_DRAGGED, 5000);
            assertThat(pane.getDividerLocation()).isEqualTo(pane.getMaximumDividerLocation())
                    .isLessThanOrEqualTo(800 - ComicDivider.THICKNESS - 50);
            mouse(jobs, MouseEvent.MOUSE_DRAGGED, -5000);
            assertThat(pane.getDividerLocation()).isEqualTo(pane.getMinimumDividerLocation()).isEqualTo(50);
            mouse(jobs, MouseEvent.MOUSE_DRAGGED, 1000);
            mouse(jobs, MouseEvent.MOUSE_RELEASED, 1000);
            assertThat(pane.getDividerLocation()).isEqualTo(placed);
            assertThat(events).isEmpty();

            // Unter der Schwelle bleibt es ein Klick: Der Trenner steht, der Knopf schaltet um.
            mouse(jobs, MouseEvent.MOUSE_PRESSED, 1000);
            mouse(jobs, MouseEvent.MOUSE_DRAGGED, 1001);
            mouse(jobs, MouseEvent.MOUSE_RELEASED, 1001);
            assertThat(pane.getDividerLocation()).isEqualTo(500 - far);
            assertThat(events).containsExactly("wahl Aufträge");

            // Ohne Trenner (Leiste zugeklappt) steht dieselbe Knopfleiste am Fensterrand.
            pane.setDividerSize(0);
            layoutAll(overlay);
            assertThat(grip.onDivider()).isFalse();
            slot = overlay.right().getBounds();
            assertThat(slot.x + slot.width).isEqualTo(overlay.getWidth() - EdgeOverlay.FRAME_LINE);

            // Abgeschaltet (Leiste ohne Umschalter) zeigt der Trenner wieder seinen gelben Griff.
            pane.setDividerSize(ComicDivider.THICKNESS);
            grip.active(false);
            assertThat(ComicDivider.grip(pane)).isTrue();
            assertThat(ComicDivider.gap(pane)).as("ohne Knopfleiste läuft die Linie durch").isZero();
            assertThat(grip.onDivider()).isFalse();
        });
    }

    @Test
    void everyLookPaintsTheRailInItsOwnColours() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RailLook before = RailLook.current();
            try {
                SegmentedToggle rail = new SegmentedToggle().segment("Chat", ICON).segment("Aufträge", ICON);
                rail.vertical(true);
                rail.clear();
                rail.setSize(rail.getPreferredSize());
                layoutAll(rail);
                for (RailLook look : RailLook.values()) {
                    RailLook.choose(look);
                    assertThat(RailLook.current()).isEqualTo(look);
                    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(rail.getWidth(),
                            rail.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = image.createGraphics();
                    rail.button("Chat").dispatchEvent(new MouseEvent(rail.button("Chat"), MouseEvent.MOUSE_ENTERED,
                            0, 0, 2, 2, 0, false));
                    rail.paint(g);
                    g.dispose();
                    // Grund der zweiten, nicht gewählten Hälfte: knapp neben ihrem Symbol.
                    java.awt.Color ground = new java.awt.Color(image.getRGB(rail.getWidth() / 2,
                            rail.getHeight() - 5), true);
                    assertThat(ground).as(look.label()).isEqualTo(look.ground());
                }
            } finally {
                RailLook.choose(before);
            }
        });
    }
}
