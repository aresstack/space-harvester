package com.aresstack.spaceharvester.app.ui;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DoubleShiftShortcutTest {
    private final JPanel source = new JPanel();
    private final DoubleShiftShortcut.TapSequence taps = new DoubleShiftShortcut.TapSequence();

    @Test
    void recognizesTwoCompleteTapsAndResetsAfterTrigger() {
        assertThat(tap(100, 140)).isFalse();
        assertThat(tap(300, 340)).isTrue();
        assertThat(tap(400, 440)).isFalse();
        assertThat(tap(600, 640)).isTrue();
    }

    @Test
    void rejectsSlowAndLongTaps() {
        assertThat(tap(100, 140)).isFalse();
        assertThat(tap(800, 840)).isFalse();
        assertThat(tap(900, 1500)).isFalse();
        assertThat(tap(1600, 1640)).isFalse();
        assertThat(tap(1700, 1740)).isTrue();
    }

    @Test
    void ignoresAutoRepeatAndUnmatchedReleases() {
        assertThat(release(100)).isFalse();
        assertThat(press(150)).isFalse();
        assertThat(press(160)).isFalse();
        assertThat(release(200)).isFalse();
        assertThat(release(210)).isFalse();
        assertThat(tap(300, 340)).isFalse();
        assertThat(tap(400, 440)).isTrue();
    }

    @Test
    void typingAndModifiedShortcutsBreakTheSequence() {
        assertThat(tap(100, 140)).isFalse();
        assertThat(press(200)).isFalse();
        assertThat(taps.accept(event(KeyEvent.KEY_PRESSED, 210, InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_A, KeyEvent.KEY_LOCATION_STANDARD))).isFalse();
        assertThat(release(240)).isFalse();
        assertThat(tap(260, 280)).isFalse();
        assertThat(taps.accept(event(KeyEvent.KEY_PRESSED, 300,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_LEFT))).isFalse();
        assertThat(release(320)).isFalse();
        assertThat(tap(350, 370)).isFalse();
        assertThat(tap(400, 420)).isTrue();
    }

    @Test
    void rejectsOverlappingShiftKeysAndResetsOnFocusChange() {
        assertThat(tap(100, 140)).isFalse();
        assertThat(press(200)).isFalse();
        assertThat(taps.accept(event(KeyEvent.KEY_PRESSED, 210, InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_RIGHT))).isFalse();
        assertThat(taps.accept(event(KeyEvent.KEY_RELEASED, 220, InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_LEFT))).isFalse();
        assertThat(taps.accept(event(KeyEvent.KEY_RELEASED, 230, 0,
                KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_RIGHT))).isFalse();
        assertThat(tap(250, 270)).isFalse();
        taps.reset();
        assertThat(tap(300, 320)).isFalse();
        assertThat(tap(350, 370)).isTrue();
    }

    private boolean tap(long from, long to) {
        assertThat(press(from)).isFalse();
        return release(to);
    }

    private boolean press(long time) {
        return taps.accept(event(KeyEvent.KEY_PRESSED, time, InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_LEFT));
    }

    private boolean release(long time) {
        return taps.accept(event(KeyEvent.KEY_RELEASED, time, 0,
                KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_LEFT));
    }

    private KeyEvent event(int id, long when, int modifiers, int key, int location) {
        return new KeyEvent(source, id, when, modifiers, key, KeyEvent.CHAR_UNDEFINED, location);
    }
}
