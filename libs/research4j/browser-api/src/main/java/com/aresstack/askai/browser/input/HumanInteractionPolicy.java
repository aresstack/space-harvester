package com.aresstack.askai.browser.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * #44 — the ONE central timing/path policy for human-paced browser input. Every mouse path,
 * click pause, typing delay and scroll step comes from here; browser/agent code never rolls
 * its own randomness or delays (no scattered magic numbers). The randomness source is
 * INJECTED and therefore seedable: tests pin exact sequences, production uses a fresh seed
 * per session so no two runs produce identical curves.
 *
 * <p>This is an execution detail of the browser layer — never an agent tool, never part of
 * the research state model.</p>
 */
public final class HumanInteractionPolicy {

    /** All timings in ONE place; every value can be overridden via {@code ASKAI_HUMAN_*} env. */
    public static final class Config {
        public final int minWaypoints;
        public final int maxWaypoints;
        public final int jitterPx;
        public final long moveStepMinMillis;
        public final long moveStepMaxMillis;
        public final long preClickPauseMinMillis;
        public final long preClickPauseMaxMillis;
        public final long postClickPauseMinMillis;
        public final long postClickPauseMaxMillis;
        public final long perCharDelayMinMillis;
        public final long perCharDelayMaxMillis;
        public final long wordPauseMinMillis;
        public final long wordPauseMaxMillis;
        public final long preSubmitPauseMinMillis;
        public final long preSubmitPauseMaxMillis;
        public final int scrollStepPx;
        public final long scrollStepPauseMinMillis;
        public final long scrollStepPauseMaxMillis;
        /** Sporadic small reading-time mouse movements — off by default, never a click. */
        public final boolean idleWiggleEnabled;
        public final int idleWiggleRadiusPx;

        Config(int minWaypoints, int maxWaypoints, int jitterPx,
               long moveStepMinMillis, long moveStepMaxMillis,
               long preClickPauseMinMillis, long preClickPauseMaxMillis,
               long postClickPauseMinMillis, long postClickPauseMaxMillis,
               long perCharDelayMinMillis, long perCharDelayMaxMillis,
               long wordPauseMinMillis, long wordPauseMaxMillis,
               long preSubmitPauseMinMillis, long preSubmitPauseMaxMillis,
               int scrollStepPx, long scrollStepPauseMinMillis, long scrollStepPauseMaxMillis,
               boolean idleWiggleEnabled, int idleWiggleRadiusPx) {
            this.minWaypoints = minWaypoints;
            this.maxWaypoints = maxWaypoints;
            this.jitterPx = jitterPx;
            this.moveStepMinMillis = moveStepMinMillis;
            this.moveStepMaxMillis = moveStepMaxMillis;
            this.preClickPauseMinMillis = preClickPauseMinMillis;
            this.preClickPauseMaxMillis = preClickPauseMaxMillis;
            this.postClickPauseMinMillis = postClickPauseMinMillis;
            this.postClickPauseMaxMillis = postClickPauseMaxMillis;
            this.perCharDelayMinMillis = perCharDelayMinMillis;
            this.perCharDelayMaxMillis = perCharDelayMaxMillis;
            this.wordPauseMinMillis = wordPauseMinMillis;
            this.wordPauseMaxMillis = wordPauseMaxMillis;
            this.preSubmitPauseMinMillis = preSubmitPauseMinMillis;
            this.preSubmitPauseMaxMillis = preSubmitPauseMaxMillis;
            this.scrollStepPx = scrollStepPx;
            this.scrollStepPauseMinMillis = scrollStepPauseMinMillis;
            this.scrollStepPauseMaxMillis = scrollStepPauseMaxMillis;
            this.idleWiggleEnabled = idleWiggleEnabled;
            this.idleWiggleRadiusPx = idleWiggleRadiusPx;
        }

        /** Documented defaults; every knob overridable via env — never a hidden constant. */
        public static Config fromEnvironment(Map<String, String> env) {
            return new Config(
                    intOf(env, "ASKAI_HUMAN_MOVE_WAYPOINTS_MIN", 8),
                    intOf(env, "ASKAI_HUMAN_MOVE_WAYPOINTS_MAX", 22),
                    intOf(env, "ASKAI_HUMAN_MOVE_JITTER_PX", 6),
                    longOf(env, "ASKAI_HUMAN_MOVE_STEP_MIN_MS", 8),
                    longOf(env, "ASKAI_HUMAN_MOVE_STEP_MAX_MS", 28),
                    longOf(env, "ASKAI_HUMAN_PRE_CLICK_MIN_MS", 120),
                    longOf(env, "ASKAI_HUMAN_PRE_CLICK_MAX_MS", 420),
                    longOf(env, "ASKAI_HUMAN_POST_CLICK_MIN_MS", 90),
                    longOf(env, "ASKAI_HUMAN_POST_CLICK_MAX_MS", 320),
                    longOf(env, "ASKAI_HUMAN_CHAR_DELAY_MIN_MS", 40),
                    longOf(env, "ASKAI_HUMAN_CHAR_DELAY_MAX_MS", 160),
                    longOf(env, "ASKAI_HUMAN_WORD_PAUSE_MIN_MS", 120),
                    longOf(env, "ASKAI_HUMAN_WORD_PAUSE_MAX_MS", 380),
                    longOf(env, "ASKAI_HUMAN_PRE_SUBMIT_MIN_MS", 350),
                    longOf(env, "ASKAI_HUMAN_PRE_SUBMIT_MAX_MS", 900),
                    intOf(env, "ASKAI_HUMAN_SCROLL_STEP_PX", 260),
                    longOf(env, "ASKAI_HUMAN_SCROLL_PAUSE_MIN_MS", 60),
                    longOf(env, "ASKAI_HUMAN_SCROLL_PAUSE_MAX_MS", 240),
                    "true".equalsIgnoreCase(env == null ? null
                            : env.get("ASKAI_HUMAN_IDLE_WIGGLE")),
                    intOf(env, "ASKAI_HUMAN_IDLE_WIGGLE_RADIUS_PX", 14));
        }

        private static int intOf(Map<String, String> env, String key, int fallback) {
            try {
                String value = env == null ? null : env.get(key);
                return value == null || value.trim().isEmpty()
                        ? fallback : Math.max(1, Integer.parseInt(value.trim()));
            } catch (NumberFormatException invalid) {
                return fallback;
            }
        }

        private static long longOf(Map<String, String> env, String key, long fallback) {
            try {
                String value = env == null ? null : env.get(key);
                return value == null || value.trim().isEmpty()
                        ? fallback : Math.max(0L, Long.parseLong(value.trim()));
            } catch (NumberFormatException invalid) {
                return fallback;
            }
        }
    }

    /** One timed pointer waypoint: move to (x, y), then wait {@code pauseMillis}. */
    public static final class TimedPoint {
        public final int x;
        public final int y;
        public final long pauseMillis;

        TimedPoint(int x, int y, long pauseMillis) {
            this.x = x;
            this.y = y;
            this.pauseMillis = pauseMillis;
        }
    }

    /** One scroll step: wheel by {@code deltaY}, then wait {@code pauseMillis}. */
    public static final class ScrollStep {
        public final int deltaY;
        public final long pauseMillis;

        ScrollStep(int deltaY, long pauseMillis) {
            this.deltaY = deltaY;
            this.pauseMillis = pauseMillis;
        }
    }

    private final Config config;
    private final Random random;

    public HumanInteractionPolicy(Config config, Random random) {
        this.config = config;
        this.random = random;
    }

    public Config config() {
        return config;
    }

    /**
     * A human-paced pointer path: several waypoints (never a teleport), smoothstep easing
     * (acceleration then deceleration), bounded jitter along the way — and the EXACT target as
     * the final point, always. Repeated calls yield different curves (the injected randomness
     * decides), a fixed seed reproduces them for tests.
     */
    public List<TimedPoint> mousePath(int fromX, int fromY, int toX, int toY) {
        int span = config.maxWaypoints - config.minWaypoints;
        int waypoints = config.minWaypoints + (span <= 0 ? 0 : random.nextInt(span + 1));
        List<TimedPoint> path = new ArrayList<TimedPoint>(waypoints + 1);
        for (int step = 1; step < waypoints; step++) {
            double linear = step / (double) waypoints;
            // Smoothstep: slow start, fast middle, slow arrival — never constant velocity.
            double eased = linear * linear * (3 - 2 * linear);
            int x = fromX + (int) Math.round((toX - fromX) * eased) + jitter();
            int y = fromY + (int) Math.round((toY - fromY) * eased) + jitter();
            path.add(new TimedPoint(x, y, between(
                    config.moveStepMinMillis, config.moveStepMaxMillis)));
        }
        // The target itself is exact — reachability is never sacrificed to realism.
        path.add(new TimedPoint(toX, toY, between(
                config.moveStepMinMillis, config.moveStepMaxMillis)));
        return path;
    }

    /** The short variable reaction pause before a click. */
    public long preClickPauseMillis() {
        return between(config.preClickPauseMinMillis, config.preClickPauseMaxMillis);
    }

    /** The short variable pause after a click. */
    public long postClickPauseMillis() {
        return between(config.postClickPauseMinMillis, config.postClickPauseMaxMillis);
    }

    /** The longer variable pause before a submit-like action. */
    public long preSubmitPauseMillis() {
        return between(config.preSubmitPauseMinMillis, config.preSubmitPauseMaxMillis);
    }

    /**
     * Per-character delays for typing {@code text}: small variable gaps, larger pauses after
     * spaces (word boundaries). Paste stays a separate explicit fast path — this is only for
     * typed input.
     */
    public List<Long> typingDelaysMillis(String text) {
        List<Long> delays = new ArrayList<Long>(text == null ? 0 : text.length());
        if (text == null) {
            return delays;
        }
        for (int index = 0; index < text.length(); index++) {
            boolean afterWord = index > 0 && text.charAt(index - 1) == ' ';
            delays.add(afterWord
                    ? between(config.wordPauseMinMillis, config.wordPauseMaxMillis)
                    : between(config.perCharDelayMinMillis, config.perCharDelayMaxMillis));
        }
        return delays;
    }

    /** Scrolling in several bounded steps instead of one instant jump. */
    public List<ScrollStep> scrollSteps(int totalDeltaY) {
        List<ScrollStep> steps = new ArrayList<ScrollStep>();
        int remaining = Math.abs(totalDeltaY);
        int direction = totalDeltaY >= 0 ? 1 : -1;
        while (remaining > 0) {
            int step = Math.min(remaining,
                    config.scrollStepPx / 2 + random.nextInt(Math.max(1, config.scrollStepPx)));
            steps.add(new ScrollStep(direction * step, between(
                    config.scrollStepPauseMinMillis, config.scrollStepPauseMaxMillis)));
            remaining -= step;
        }
        return steps;
    }

    /**
     * A sporadic reading-time wiggle NEAR the current position: a few small timed moves that
     * end back where they started — never a click, never a travel onto a control (the caller
     * passes a position it knows is safe). Empty when disabled.
     */
    public List<TimedPoint> idleWiggle(int currentX, int currentY) {
        if (!config.idleWiggleEnabled) {
            return new ArrayList<TimedPoint>();
        }
        List<TimedPoint> moves = new ArrayList<TimedPoint>();
        int radius = Math.max(1, config.idleWiggleRadiusPx);
        int hops = 2 + random.nextInt(3);
        for (int hop = 0; hop < hops; hop++) {
            moves.add(new TimedPoint(
                    currentX + random.nextInt(radius * 2 + 1) - radius,
                    currentY + random.nextInt(radius * 2 + 1) - radius,
                    between(config.moveStepMinMillis * 4, config.moveStepMaxMillis * 8)));
        }
        moves.add(new TimedPoint(currentX, currentY,
                between(config.moveStepMinMillis, config.moveStepMaxMillis)));
        return moves;
    }

    private int jitter() {
        return config.jitterPx <= 0 ? 0
                : random.nextInt(config.jitterPx * 2 + 1) - config.jitterPx;
    }

    private long between(long min, long max) {
        return max <= min ? min : min + (long) (random.nextDouble() * (max - min));
    }
}
