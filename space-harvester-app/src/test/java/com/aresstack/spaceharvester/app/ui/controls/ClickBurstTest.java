package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;

/** Der versteckte Auslöser zählt nur dicht aufeinander folgende Linksklicks. */
class ClickBurstTest {

    @Test
    void fiveQuickClicksFireOnceSlowOrOtherButtonsDoNot() {
        JLabel target = new JLabel();
        AtomicInteger fired = new AtomicInteger();
        ClickBurst.install(target, 5, Duration.ofMillis(700), fired::incrementAndGet);
        long t = 1_000;
        for (int i = 0; i < 4; i++) {
            press(target, t += 300, MouseEvent.BUTTON1);
        }
        press(target, t += 2_000, MouseEvent.BUTTON1); // zu langsam: beginnt von vorn
        assertThat(fired).hasValue(0);
        for (int i = 0; i < 3; i++) {
            press(target, t += 300, MouseEvent.BUTTON1);
        }
        press(target, t += 100, MouseEvent.BUTTON3); // Rechtsklick zählt nicht
        assertThat(fired).hasValue(0);
        press(target, t += 300, MouseEvent.BUTTON1);
        assertThat(fired).hasValue(1);
        press(target, t += 300, MouseEvent.BUTTON1);
        assertThat(fired).as("zählt danach neu").hasValue(1);
    }

    private static void press(JLabel target, long when, int button) {
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, when, 0, 2, 2, 1, false, button));
    }
}
