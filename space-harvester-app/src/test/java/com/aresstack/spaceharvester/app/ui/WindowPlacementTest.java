package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowPlacementTest {

    private static final Dimension MIN = new Dimension(960, 600);
    /** Hauptbildschirm 1920×1040 (Taskleiste unten), zweiter Bildschirm links daneben. */
    private static final Rectangle MAIN = new Rectangle(0, 0, 1920, 1040);
    private static final Rectangle LEFT = new Rectangle(-1920, 0, 1920, 1080);

    @Test
    void aWindowThatFitsStaysWhereItWas() {
        Rectangle saved = new Rectangle(200, 100, 1280, 820);
        assertThat(WindowPlacement.fit(saved, List.of(MAIN, LEFT), MIN)).isEqualTo(saved);
        Rectangle onLeft = new Rectangle(-1700, 50, 1280, 820);
        assertThat(WindowPlacement.fit(onLeft, List.of(MAIN, LEFT), MIN)).isEqualTo(onLeft);
    }

    @Test
    void aWindowOnAMonitorThatIsGoneComesBackCenteredOnTheMainScreen() {
        Rectangle saved = new Rectangle(-1700, 50, 1280, 820);
        assertThat(WindowPlacement.fit(saved, List.of(MAIN), MIN))
                .isEqualTo(new Rectangle(320, 110, 1280, 820));
    }

    @Test
    void aWindowLargerThanTheScreenShrinksToTheUsableArea() {
        Rectangle saved = new Rectangle(-10, -10, 2560, 1400);
        assertThat(WindowPlacement.fit(saved, List.of(MAIN), MIN)).isEqualTo(MAIN);
    }

    @Test
    void aWindowHangingOverTheEdgeIsPushedFullyOntoItsScreen() {
        Rectangle saved = new Rectangle(1500, 700, 1280, 820);
        assertThat(WindowPlacement.fit(saved, List.of(MAIN), MIN))
                .isEqualTo(new Rectangle(640, 220, 1280, 820));
    }

    @Test
    void theWindowGoesToTheScreenThatHoldsMostOfIt() {
        Rectangle saved = new Rectangle(-400, 100, 1280, 820);
        assertThat(WindowPlacement.fit(saved, List.of(MAIN, LEFT), MIN))
                .isEqualTo(new Rectangle(0, 100, 1280, 820));
    }

    @Test
    void aSlimSliverOnAScreenIsNotEnoughToStayThere() {
        Rectangle saved = new Rectangle(1880, 100, 1280, 820);
        assertThat(WindowPlacement.fit(saved, List.of(MAIN), MIN))
                .isEqualTo(new Rectangle(320, 110, 1280, 820));
    }

    @Test
    void theMinimumSizeHoldsWhileTheScreenIsLargeEnough() {
        Rectangle tiny = new Rectangle(100, 100, 300, 200);
        assertThat(WindowPlacement.fit(tiny, List.of(MAIN), MIN)).isEqualTo(new Rectangle(100, 100, 960, 600));
        Rectangle small = new Rectangle(0, 0, 800, 560);
        assertThat(WindowPlacement.fit(new Rectangle(0, 0, 1280, 820), List.of(small), MIN)).isEqualTo(small);
    }
}
