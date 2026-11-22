package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;

/**
 * Ein versteckter Auslöser: {@code clicks} Linksklicks auf eine Fläche, jeder höchstens {@code gap} nach dem vorigen,
 * rufen die Aktion. Zählt nur über die Zeitstempel der Ereignisse, ohne Zeitgeber. Weiß nichts davon, was ausgelöst
 * wird, und lässt sich daher jederzeit gegen einen anderen Zugang tauschen.
 */
public final class ClickBurst extends MouseAdapter {

    private final int clicks;
    private final long gapMillis;
    private final Runnable action;
    private int count;
    private long last;

    private ClickBurst(int clicks, Duration gap, Runnable action) {
        this.clicks = clicks;
        this.gapMillis = gap.toMillis();
        this.action = action;
    }

    /** Hängt den Auslöser an {@code target} und gibt ihn zurück (zum Entfernen mit removeMouseListener). */
    public static ClickBurst install(Component target, int clicks, Duration gap, Runnable action) {
        ClickBurst burst = new ClickBurst(clicks, gap, action);
        target.addMouseListener(burst);
        return burst;
    }

    @Override
    public void mousePressed(MouseEvent event) {
        if (event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        long when = event.getWhen();
        count = count > 0 && when - last <= gapMillis ? count + 1 : 1;
        last = when;
        if (count >= clicks) {
            count = 0;
            action.run();
        }
    }
}
