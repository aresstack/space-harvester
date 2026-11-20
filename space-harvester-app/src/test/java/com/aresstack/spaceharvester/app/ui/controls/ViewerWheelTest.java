package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Das Mausrad im Betrachter: allein blättert es wie die Pfeile, bei hineingezoomtem Inhalt verschiebt es erst, Strg
 * zoomt, Shift verschiebt waagrecht (Angelo, 20.11.2026).
 */
class ViewerWheelTest {

    private final JPanel source = new JPanel();
    private final List<String> calls = new ArrayList<>();
    private boolean room;
    private long now;

    private final ViewerWheel wheel = wheel();

    private ViewerWheel wheel() {
        return new ViewerWheel(new ViewerWheel.Target() {
            @Override
            public boolean pan(int dx, int dy) {
                calls.add("pan " + dx + "," + dy);
                return room;
            }

            @Override
            public void zoom(double factor, Point at) {
                calls.add("zoom " + (factor > 1 ? "in" : "out") + " " + at.x + "," + at.y);
            }

            @Override
            public void previous() {
                calls.add("previous");
            }

            @Override
            public void next() {
                calls.add("next");
            }
        }, () -> now);
    }

    @BeforeEach
    void freshGap() {
        ViewerWheel.resetPageGap();
    }

    @Test
    void wheelAloneTurnsThePageLikeTheArrows() {
        wheel.mouseWheelMoved(event(1, 0));
        now += 1000;
        wheel.mouseWheelMoved(event(-1, 0));
        assertThat(calls).containsExactly("pan 0,-60", "next", "pan 0,60", "previous");
    }

    @Test
    void wheelMovesAZoomedPictureFirstAndTurnsOnlyAtItsEdge() {
        room = true;
        wheel.mouseWheelMoved(event(1, 0));
        assertThat(calls).containsExactly("pan 0,-60");
        room = false;
        now += 1000;
        wheel.mouseWheelMoved(event(1, 0));
        assertThat(calls).containsExactly("pan 0,-60", "pan 0,-60", "next");
    }

    @Test
    void manySmallTouchpadStepsTurnOnePageAtATime() {
        wheel.mouseWheelMoved(event(1, 0));
        now += 50;
        wheel.mouseWheelMoved(event(1, 0));
        now += 50;
        wheel.mouseWheelMoved(event(1, 0));
        assertThat(calls).filteredOn("next"::equals).hasSize(1);
        now += 300;
        wheel.mouseWheelMoved(event(1, 0));
        assertThat(calls).filteredOn("next"::equals).hasSize(2);
    }

    /** Der nächste Anhang öffnet einen neuen Betrachter mit eigenem Rad; die Sperre gilt trotzdem weiter. */
    @Test
    void theGapSurvivesAReplacedViewer() {
        wheel.mouseWheelMoved(event(1, 0));
        now += 50;
        wheel().mouseWheelMoved(event(1, 0));
        assertThat(calls).filteredOn("next"::equals).hasSize(1);
        now += 300;
        wheel().mouseWheelMoved(event(1, 0));
        assertThat(calls).filteredOn("next"::equals).hasSize(2);
    }

    @Test
    void controlWheelZoomsAtThePointer() {
        MouseWheelEvent event = event(-1, InputEvent.CTRL_DOWN_MASK);
        wheel.mouseWheelMoved(event);
        wheel.mouseWheelMoved(event(1, InputEvent.CTRL_DOWN_MASK));
        assertThat(calls).containsExactly("zoom in 40,30", "zoom out 40,30");
        assertThat(event.isConsumed()).isTrue();
    }

    @Test
    void shiftWheelMovesSidewaysAndNeverTurnsThePage() {
        wheel.mouseWheelMoved(event(1, InputEvent.SHIFT_DOWN_MASK));
        wheel.mouseWheelMoved(event(-1, InputEvent.SHIFT_DOWN_MASK));
        assertThat(calls).containsExactly("pan -60,0", "pan 60,0");
    }

    @Test
    void panToKeepsTheContentWithinTheViewWithoutSnappingBack() {
        // passt hinein: steht
        assertThat(ViewerWheel.panTo(10, -50, 100, 200)).isEqualTo(10);
        // größer als die Sicht: bis zum Rand, nicht weiter
        assertThat(ViewerWheel.panTo(0, -60, 500, 200)).isEqualTo(-60);
        assertThat(ViewerWheel.panTo(-280, -400, 500, 200)).isEqualTo(-300);
        assertThat(ViewerWheel.panTo(-20, 60, 500, 200)).isEqualTo(0);
        // vom Benutzer hinausgezogen: wandert nur zurück, springt nicht
        assertThat(ViewerWheel.panTo(80, 140, 500, 200)).isEqualTo(80);
        assertThat(ViewerWheel.panTo(80, 20, 500, 200)).isEqualTo(20);
    }

    private MouseWheelEvent event(int rotation, int modifiers) {
        return new MouseWheelEvent(source, MouseWheelEvent.MOUSE_WHEEL, 0L, modifiers, 40, 30, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
    }
}
