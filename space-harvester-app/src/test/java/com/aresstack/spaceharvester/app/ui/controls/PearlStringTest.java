package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Glyph;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Knopfleiste und Kugeln einer Seitenleiste (Angelo 28.11.2026): Symbole lassen sich herausziehen, zurückschieben und
 * umsortieren; jede Kugel öffnet ihren eigenen Bereich, und die offenen Bereiche teilen sich die Höhe.
 */
class PearlStringTest {

    private static final String CHAT = "Chat";
    private static final String ANALYSES = "Analysen";
    private static final String JOBS = "Aufträge";

    private final SegmentedToggle rail = new SegmentedToggle();
    private final PearlString dock = new PearlString(rail);
    private final List<String> changes = new ArrayList<>();

    private void threeTools() {
        dock.tool(CHAT, Glyph.STAR, "Chat zeigen").tool(ANALYSES, Glyph.TABLE, "Analysen zeigen")
                .tool(JOBS, Glyph.CROSSHAIR, "Aufträge zeigen");
        rail.vertical(true);
        rail.clear();
        dock.onChange(() -> changes.add(dock.placement()));
        layout();
    }

    private void layout() {
        dock.column().setSize(dock.column().getPreferredSize());
        layoutAll(dock.column());
    }

    private static void layoutAll(Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    @Test
    void aToolLeavesTheRailAsABallAndComesBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            threeTools();
            int railHeight = rail.getPreferredSize().height;

            dock.detach(ANALYSES, 1);

            assertThat(rail.names()).containsExactly(CHAT, JOBS);
            assertThat(dock.units()).containsExactly(PearlString.RAIL, ANALYSES);
            assertThat(dock.pearls()).containsExactly(ANALYSES);
            assertThat(dock.pearl(ANALYSES)).isTrue();
            assertThat(dock.open(ANALYSES)).as("war nicht gewählt: die Kugel ist zu").isFalse();
            assertThat(rail.getPreferredSize().height).isLessThan(railHeight);
            AbstractButton ball = dock.button(ANALYSES);
            assertThat(ball.getToolTipText()).as("der Tooltip wandert mit").isEqualTo("Analysen zeigen");
            assertThat(ball.getPreferredSize().width).as("eine Kugel ist so hoch wie breit")
                    .isEqualTo(ball.getPreferredSize().height);
            assertThat(((SegmentedToggle) ball.getParent()).glassy()).as("Kugeln sind leicht durchscheinend").isTrue();
            assertThat(changes).containsExactly("R,1:0");

            // Ein Klick öffnet den eigenen Bereich, ein zweiter schließt ihn; die Leiste bleibt unberührt.
            ball.doClick();
            assertThat(dock.open(ANALYSES)).isTrue();
            assertThat(dock.openPearls()).containsExactly(ANALYSES);
            assertThat(rail.selected()).isNull();
            ball.doClick();
            assertThat(dock.open(ANALYSES)).isFalse();

            // Zurück an ihren alten Platz in der Leiste.
            dock.attach(ANALYSES, 1);
            assertThat(rail.names()).containsExactly(CHAT, ANALYSES, JOBS);
            assertThat(dock.pearls()).isEmpty();
            assertThat(dock.button(ANALYSES).getParent()).isSameAs(rail);
            assertThat(dock.button(ANALYSES).getToolTipText()).isEqualTo("Analysen zeigen");
            assertThat(rail.getPreferredSize().height).isEqualTo(railHeight);
            assertThat(dock.placement()).isEmpty();
        });
    }

    @Test
    void theChosenToolStaysOpenAsABallAndReportsItWhenItComesBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            threeTools();
            rail.select(CHAT);
            List<String> attached = new ArrayList<>();
            dock.onAttach((name, wasOpen) -> attached.add(name + ":" + wasOpen));

            dock.detach(CHAT, 0);

            assertThat(dock.open(CHAT)).as("sein Bereich bleibt offen, nun als der der Kugel").isTrue();
            assertThat(rail.selected()).as("die Leiste zeigt keine Wahl mehr").isNull();
            assertThat(dock.button(CHAT).isSelected()).isTrue();

            dock.attach(CHAT, 0);
            assertThat(attached).containsExactly("Chat:true");
            assertThat(rail.names()).containsExactly(CHAT, ANALYSES, JOBS);
        });
    }

    @Test
    void ballsAreSortedAndRememberedWithTheirOpenAreas() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            threeTools();
            dock.detach(CHAT, 1);
            dock.detach(JOBS, 2);
            dock.open(JOBS, true);
            assertThat(dock.units()).containsExactly(PearlString.RAIL, CHAT, JOBS);

            // Die Reihenfolge der Gruppen ist die ihrer Bereiche: Die unterste Kugel nach oben geschoben steht über
            // der Leiste.
            dock.order(JOBS, 0);

            assertThat(dock.units()).containsExactly(JOBS, PearlString.RAIL, CHAT);
            assertThat(dock.pearls()).containsExactly(JOBS, CHAT);
            assertThat(dock.placement()).isEqualTo("2:1,R,0:0");
            assertThat(dock.openPearls()).containsExactly(JOBS);

            // Ein neues Fenster stellt dieselben Kugeln wieder her; Unlesbares bleibt in der Leiste.
            SegmentedToggle otherRail = new SegmentedToggle();
            PearlString other = new PearlString(otherRail);
            other.tool(CHAT, Glyph.STAR, "").tool(ANALYSES, Glyph.TABLE, "").tool(JOBS, Glyph.CROSSHAIR, "");
            other.restore("2:1,R,0:0,kaputt,9:1");
            assertThat(other.units()).containsExactly(JOBS, PearlString.RAIL, CHAT);
            assertThat(other.open(JOBS)).isTrue();
            assertThat(other.open(CHAT)).isFalse();
            assertThat(otherRail.names()).containsExactly(ANALYSES);

            // Das letzte Symbol bleibt in der Leiste: Allein ist sie selbst eine Kugel und wechselt nur den Platz.
            dock.detach(ANALYSES, 0);
            assertThat(rail.names()).containsExactly(ANALYSES);
            assertThat(dock.units()).containsExactly(PearlString.RAIL, JOBS, CHAT);

            dock.closePearls();
            assertThat(dock.openPearls()).isEmpty();
            assertThat(dock.button(JOBS).isSelected()).isFalse();
        });
    }

    @Test
    void draggingAnIconOutOfTheRailMakesItABallAndDraggingItBackReturnsIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            threeTools();
            EdgeOverlay overlay = new EdgeOverlay();
            overlay.setSize(400, 600);
            overlay.place(overlay.right(), dock.column());
            dock.carrier(overlay);
            JPanel slot = (JPanel) overlay.right();
            slot.setBounds(380, 100, dock.column().getPreferredSize().width,
                    dock.column().getPreferredSize().height);
            slot.doLayout();
            layout();

            // Nach unten aus der Leiste gezogen und unter ihr losgelassen: eine Kugel.
            AbstractButton jobs = dock.button(JOBS);
            press(jobs, 5, 5);
            drag(jobs, 5, 9);
            assertThat(dock.carrying()).as("unter der Ziehschwelle ist es noch ein Klick").isFalse();
            drag(jobs, 5, 60);
            assertThat(dock.carrying()).isTrue();
            assertThat(overlay.carried()).as("das Symbol wird als Abbild getragen").isNotNull();
            assertThat(overlay.carried().getComponent(0).getWidth()).as("das Abbild ist gezeichnet, nicht leer")
                    .isGreaterThan(0);
            assertThat(rail.names()).as("erst beim Loslassen ändert sich etwas").contains(JOBS);
            release(jobs, 5, 60);

            assertThat(dock.carrying()).isFalse();
            assertThat(overlay.carried()).isNull();
            assertThat(dock.pearls()).containsExactly(JOBS);
            assertThat(rail.names()).containsExactly(CHAT, ANALYSES);
            slot.setSize(dock.column().getPreferredSize());
            slot.doLayout();
            layout();

            // Eine zweite Kugel, über der ersten losgelassen, steht über ihr.
            AbstractButton analyses = dock.button(ANALYSES);
            Component first = dock.button(JOBS).getParent();
            Point above = SwingUtilities.convertPoint(dock.column(), new Point(5, first.getY() + 2), analyses);
            press(analyses, 5, 5);
            drag(analyses, above.x, above.y);
            release(analyses, above.x, above.y);
            assertThat(dock.pearls()).containsExactly(ANALYSES, JOBS);
            slot.setSize(dock.column().getPreferredSize());
            slot.doLayout();
            layout();

            // Die untere Kugel auf die Leiste geschoben: Sie gehört wieder dazu.
            AbstractButton ball = dock.button(JOBS);
            Point onRail = SwingUtilities.convertPoint(dock.column(),
                    new Point(5, rail.getY() + rail.getHeight() - 1), ball);
            press(ball, 5, 5);
            drag(ball, onRail.x, onRail.y);
            release(ball, onRail.x, onRail.y);
            assertThat(dock.pearls()).containsExactly(ANALYSES);
            assertThat(rail.names()).containsExactly(CHAT, JOBS);
        });
    }

    @Test
    void onADividerOnlyAMoveUpOrDownPullsAnIconOut() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            threeTools();
            boolean[] dividerDragged = {false};
            dock.sideways(() -> true, () -> dividerDragged[0]);
            AbstractButton chat = dock.button(CHAT);

            press(chat, 5, 5);
            drag(chat, 40, 8);
            assertThat(dock.carrying()).as("zur Seite zieht die Leiste ihren Trenner").isFalse();
            release(chat, 40, 8);
            assertThat(dock.pearls()).isEmpty();

            dividerDragged[0] = true;
            press(chat, 5, 5);
            drag(chat, 8, 60);
            assertThat(dock.carrying()).as("solange der Trenner gezogen wird, löst sich nichts").isFalse();
            release(chat, 8, 60);

            dividerDragged[0] = false;
            press(chat, 5, 5);
            drag(chat, 8, 200);
            assertThat(dock.carrying()).isTrue();
            release(chat, 8, 200);
            assertThat(dock.pearls()).containsExactly(CHAT);
        });
    }

    private static void press(Component source, int x, int y) {
        MouseEvent event = event(source, MouseEvent.MOUSE_PRESSED, x, y);
        for (MouseListener listener : source.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    private static void drag(Component source, int x, int y) {
        MouseEvent event = event(source, MouseEvent.MOUSE_DRAGGED, x, y);
        for (MouseMotionListener listener : source.getMouseMotionListeners()) {
            listener.mouseDragged(event);
        }
    }

    private static void release(Component source, int x, int y) {
        MouseEvent event = event(source, MouseEvent.MOUSE_RELEASED, x, y);
        for (MouseListener listener : source.getMouseListeners()) {
            listener.mouseReleased(event);
        }
    }

    private static MouseEvent event(Component source, int id, int x, int y) {
        return new MouseEvent(source, id, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y, x, y, 1,
                false, MouseEvent.BUTTON1);
    }
}
