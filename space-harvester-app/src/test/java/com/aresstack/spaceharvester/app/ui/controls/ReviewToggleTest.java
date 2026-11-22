package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.RoundHover;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** ✓ und ✕ als ein Baustein (UX W3): nur Zustand, gesperrt mit Grund, Rückmeldung per Listener. */
class ReviewToggleTest {

    @Test
    void aClickSetsTheValueAndAClickOnTheSetKnobWithdrawsIt() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> heard = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ReviewToggle toggle = new ReviewToggle().onChange((from, to) -> heard.add(from + ">" + to));
            seen.add(toggle.state().name());
            toggle.clickConfirm();
            seen.add(toggle.state().name());
            toggle.clickConfirm();
            seen.add(toggle.state().name());
            toggle.clickReject();
            seen.add(toggle.state().name());
            toggle.clickConfirm();
            seen.add(toggle.state().name());
            toggle.clickReject();
            seen.add(toggle.state().name());
            toggle.clickReject();
            seen.add(toggle.state().name());
            toggle.state(ReviewToggle.State.CONFIRMED);
            seen.add(toggle.state().name());
        });
        assertThat(seen).containsExactly("OPEN", "CONFIRMED", "OPEN", "REJECTED", "CONFIRMED", "REJECTED", "OPEN",
                "CONFIRMED");
        assertThat(heard).as("nur Klicks melden sich, state(…) nicht").containsExactly("OPEN>CONFIRMED",
                "CONFIRMED>OPEN", "OPEN>REJECTED", "REJECTED>CONFIRMED", "CONFIRMED>REJECTED", "REJECTED>OPEN");
    }

    @Test
    void tooltipsAndAccessibleNamesFollowTheState() throws Exception {
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ReviewToggle toggle = new ReviewToggle();
            names(toggle, seen);
            toggle.state(ReviewToggle.State.CONFIRMED);
            names(toggle, seen);
            toggle.state(ReviewToggle.State.REJECTED);
            names(toggle, seen);
        });
        assertThat(seen).containsExactly(
                "Aussage bestätigen", "Aussage bestätigen", "Aussage verwerfen", "Aussage verwerfen",
                "Bestätigung zurücknehmen", "Bestätigung zurücknehmen", "Aussage verwerfen", "Aussage verwerfen",
                "Aussage bestätigen", "Aussage bestätigen", "Verwerfen zurücknehmen", "Verwerfen zurücknehmen");
    }

    @Test
    void lockedBothKnobsAreDisabledWithTheReasonAndIgnoreClicks() throws Exception {
        List<Object> seen = new ArrayList<>();
        List<String> heard = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ReviewToggle toggle = new ReviewToggle().onChange((from, to) -> heard.add(from + ">" + to));
            toggle.locked("Kein Recht, Aussagen dieser Seite zu prüfen");
            seen.add(toggle.confirmButton().isEnabled());
            seen.add(toggle.rejectButton().isEnabled());
            seen.add(toggle.confirmButton().getToolTipText());
            seen.add(toggle.rejectButton().getToolTipText());
            seen.add(toggle.confirmButton().getAccessibleContext().getAccessibleName());
            seen.add(toggle.rejectButton().getAccessibleContext().getAccessibleDescription());
            toggle.clickConfirm();
            toggle.clickReject();
            seen.add(toggle.state().name());
            toggle.locked(null);
            seen.add(toggle.confirmButton().isEnabled());
            seen.add(toggle.confirmButton().getToolTipText());
            seen.add(toggle.lockReason());
        });
        assertThat(seen).containsExactly(false, false, "Kein Recht, Aussagen dieser Seite zu prüfen",
                "Kein Recht, Aussagen dieser Seite zu prüfen", "Aussage bestätigen",
                "Kein Recht, Aussagen dieser Seite zu prüfen", "OPEN", true, "Aussage bestätigen", null);
        assertThat(heard).isEmpty();
    }

    @Test
    void tabReachesBothKnobsAndSpaceAndEnterToggle() throws Exception {
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ReviewToggle toggle = new ReviewToggle();
            seen.add(toggle.confirmButton().isFocusable());
            seen.add(toggle.rejectButton().isFocusable());
            press(toggle.confirmButton(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
            seen.add(toggle.state().name());
            press(toggle.rejectButton(), KeyStroke.getKeyStroke("pressed SPACE"));
            press(toggle.rejectButton(), KeyStroke.getKeyStroke("released SPACE"));
            seen.add(toggle.state().name());
            press(toggle.rejectButton(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
            seen.add(toggle.state().name());
        });
        assertThat(seen).containsExactly(true, true, "CONFIRMED", "REJECTED", "OPEN");
    }

    @Test
    void knobsHaveTheSizeOfTheTabHeaderIconButtonsAndOnlyTheSetOneIsFilledGreenOrInkNeverRed() throws Exception {
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ReviewToggle toggle = new ReviewToggle();
            seen.add(toggle.confirmButton().getPreferredSize().width);
            seen.add(toggle.rejectButton().getPreferredSize().height);
            toggle.setSize(toggle.getPreferredSize());
            toggle.doLayout();
            seen.add(pixel(toggle, 5));
            seen.add(pixel(toggle, ReviewToggle.SIZE + ReviewToggle.GAP + 5));
            toggle.state(ReviewToggle.State.CONFIRMED);
            seen.add(pixel(toggle, 5));
            toggle.state(ReviewToggle.State.REJECTED);
            seen.add(pixel(toggle, ReviewToggle.SIZE + ReviewToggle.GAP + 5));
        });
        assertThat(seen).as("ungesetzt durchsichtig: der Grund scheint durch").containsExactly(RoundHover.SIZE,
                RoundHover.SIZE, Color.MAGENTA.getRGB(), Color.MAGENTA.getRGB(), ReviewToggle.CONFIRMED_FILL.getRGB(),
                Comic.INK.getRGB());
        assertThat(ReviewToggle.REJECTED_FILL).isNotEqualTo(Comic.RED).isNotEqualTo(Comic.YELLOW);
    }

    private static void names(ReviewToggle toggle, List<String> seen) {
        for (JButton button : List.of(toggle.confirmButton(), toggle.rejectButton())) {
            seen.add(button.getToolTipText());
            seen.add(button.getAccessibleContext().getAccessibleName());
        }
    }

    private static void press(JButton button, KeyStroke stroke) {
        Object key = button.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
        assertThat(key).as("Taste " + stroke + " gebunden").isNotNull();
        button.getActionMap().get(key).actionPerformed(new ActionEvent(button, ActionEvent.ACTION_PERFORMED,
                key.toString()));
    }

    /** Farbe links in der Mitte eines Knopfs, abseits des Zeichens. */
    private static int pixel(ReviewToggle toggle, int x) {
        BufferedImage image = new BufferedImage(toggle.getWidth(), toggle.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(Color.MAGENTA);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        toggle.paint(g);
        g.dispose();
        return image.getRGB(x, toggle.getHeight() / 2) | 0xFF000000;
    }
}
