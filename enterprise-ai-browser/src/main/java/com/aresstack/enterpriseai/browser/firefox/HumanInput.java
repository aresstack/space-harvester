package com.aresstack.enterpriseai.browser.firefox;

import de.bund.zrb.command.request.parameters.input.sourceActions.KeySourceAction;
import de.bund.zrb.command.request.parameters.input.sourceActions.PauseAction;
import de.bund.zrb.command.request.parameters.input.sourceActions.PointerSourceAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The human variation of the BiDi input (#141 "Human Emulation", "Mausbewegungen"): uneven pauses between keys,
 * scroll steps and a reading pause; mouse paths that approach the target on a curved detour with slight tremor.
 * Everything here becomes WebDriver BiDi input actions or page scripts inside Firefox: the browser's own synthetic
 * pointer, never the operating system's cursor or keyboard. The random source is injected so tests can use a seed.
 */
final class HumanInput {

    private final Random random;
    private final boolean keys;
    private final boolean mouse;

    HumanInput(Random random, boolean keys, boolean mouse) {
        this.random = random;
        this.keys = keys;
        this.mouse = mouse;
    }

    /** No variation: straight moves, keys without pauses, no scrolling or reading. */
    static HumanInput off() {
        return new HumanInput(new Random(0L), false, false);
    }

    boolean keys() {
        return keys;
    }

    boolean mouse() {
        return mouse;
    }

    /** Pause between two keystrokes in ms (0 when off): mostly 50–170, now and then a longer hesitation. */
    int keyPause() {
        if (!keys) {
            return 0;
        }
        int base = 50 + random.nextInt(121);
        return random.nextInt(12) == 0 ? base + 200 + random.nextInt(300) : base;
    }

    /**
     * The moves from ({@code fromX}, {@code fromY}) to the target: one straight move when the mouse is off, else
     * points along a quadratic curve through a random control point beside the line, each with a little tremor and a
     * short pause, ending exactly on the target.
     */
    List<PointerSourceAction> path(double fromX, double fromY, long toX, long toY) {
        List<PointerSourceAction> moves = new ArrayList<PointerSourceAction>();
        if (!mouse) {
            moves.add(new PointerSourceAction.PointerMoveAction(toX, toY));
            return moves;
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        double length = Math.max(1.0, Math.sqrt(dx * dx + dy * dy));
        // Control point beside the middle of the line, up to 40 % of its length away, either side.
        double bend = (random.nextDouble() * 0.8 - 0.4) * length;
        double cx = fromX + dx / 2 - dy / length * bend;
        double cy = fromY + dy / 2 + dx / length * bend;
        int steps = 10 + random.nextInt(11);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = (1 - t) * (1 - t) * fromX + 2 * (1 - t) * t * cx + t * t * toX + tremor();
            double y = (1 - t) * (1 - t) * fromY + 2 * (1 - t) * t * cy + t * t * toY + tremor();
            moves.add(new PointerSourceAction.PointerMoveAction(Math.max(0, Math.round(x)), Math.max(0, Math.round(y))));
            moves.add(new PauseAction(8 + random.nextInt(18)));
        }
        moves.add(new PointerSourceAction.PointerMoveAction(toX, toY));
        moves.add(new PauseAction(40 + random.nextInt(120)));
        return moves;
    }

    /** A slight hand tremor in pixels, about ±2. */
    private double tremor() {
        return random.nextGaussian() * 1.2;
    }

    /** Where the pointer starts in a tab it has not moved in yet: somewhere in the upper part of the page. */
    double[] startPoint() {
        return new double[]{100 + random.nextInt(600), 80 + random.nextInt(300)};
    }

    /** A few moves over the text while reading (mouse on), ending where they started. */
    List<PointerSourceAction> wander(double x, double y) {
        List<PointerSourceAction> moves = new ArrayList<PointerSourceAction>();
        if (!mouse) {
            return moves;
        }
        int count = 2 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            moves.add(new PointerSourceAction.PointerMoveAction(Math.max(0, Math.round(x + random.nextInt(240) - 120)),
                    Math.max(0, Math.round(y + random.nextInt(120) - 40))));
            moves.add(new PauseAction(120 + random.nextInt(250)));
        }
        moves.add(new PointerSourceAction.PointerMoveAction(Math.round(x), Math.round(y)));
        return moves;
    }

    /** Uneven scroll steps in pixels for a page of {@code pageHeight}; empty when off or the page fits. */
    List<Integer> scrollSteps(int pageHeight, int viewportHeight) {
        List<Integer> steps = new ArrayList<Integer>();
        if (!keys || pageHeight <= viewportHeight) {
            return steps;
        }
        int count = 2 + random.nextInt(3);
        int reach = Math.min(pageHeight - viewportHeight, viewportHeight * 3);
        for (int i = 0; i < count; i++) {
            steps.add(Math.max(40, reach / count + random.nextInt(Math.max(1, viewportHeight / 4))
                    - viewportHeight / 8));
        }
        return steps;
    }

    /** Pause between two scroll steps in ms. */
    int scrollPause() {
        return 150 + random.nextInt(400);
    }

    /** A reading pause for {@code textLength} visible characters (0 when off): 300 ms plus a little per word, ≤ 2.5 s. */
    int readingPause(int textLength) {
        if (!keys) {
            return 0;
        }
        return Math.min(2500, 300 + textLength / 40 + random.nextInt(300));
    }

    /** Inserts the key pauses into a sequence of key actions (after every key up). */
    List<KeySourceAction> withKeyPauses(List<KeySourceAction> actions) {
        if (!keys) {
            return actions;
        }
        List<KeySourceAction> out = new ArrayList<KeySourceAction>();
        for (KeySourceAction action : actions) {
            out.add(action);
            if (action instanceof KeySourceAction.KeyUpAction) {
                out.add(new PauseAction(keyPause()));
            }
        }
        return out;
    }
}
