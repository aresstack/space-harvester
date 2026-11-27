package com.aresstack.askai.browser.input;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * #44 pins: no pointer teleports (several timed waypoints, exact target last, bounded
 * jitter), variable but bounded delays from ONE central policy, non-identical curves across
 * seeds, full seeded reproducibility, word-boundary typing pauses, stepped scrolling — and
 * the executor's event SEQUENCE against a fake device.
 */
public class HumanInteractionPolicyTest {

    private static HumanInteractionPolicy policy(long seed) {
        return new HumanInteractionPolicy(
                HumanInteractionPolicy.Config.fromEnvironment(
                        Collections.<String, String>emptyMap()),
                new Random(seed));
    }

    @Test
    public void aMouseMoveIsManyTimedWaypointsEndingExactlyOnTheTarget() {
        List<HumanInteractionPolicy.TimedPoint> path =
                policy(1L).mousePath(10, 10, 600, 400);
        assertTrue("never a teleport: " + path.size() + " waypoints", path.size() >= 8);
        HumanInteractionPolicy.TimedPoint last = path.get(path.size() - 1);
        assertEquals("the target itself is exact", 600, last.x);
        assertEquals(400, last.y);
        for (HumanInteractionPolicy.TimedPoint point : path) {
            assertTrue("every step is timed", point.pauseMillis >= 8);
            assertTrue(point.pauseMillis <= 28);
            // Jitter is bounded: waypoints stay near the straight line's bounding box.
            assertTrue(point.x >= 10 - 6 && point.x <= 600 + 6);
            assertTrue(point.y >= 10 - 6 && point.y <= 400 + 6);
        }
    }

    @Test
    public void curvesAreNeverDeterministicallyIdenticalButSeedsReproduce() {
        List<HumanInteractionPolicy.TimedPoint> first =
                policy(1L).mousePath(0, 0, 300, 200);
        List<HumanInteractionPolicy.TimedPoint> second =
                policy(2L).mousePath(0, 0, 300, 200);
        assertFalse("different seeds → different curves", sameCurve(first, second));
        List<HumanInteractionPolicy.TimedPoint> reproduced =
                policy(1L).mousePath(0, 0, 300, 200);
        assertTrue("the SAME seed reproduces exactly (testability)",
                sameCurve(first, reproduced));
    }

    @Test
    public void typingHasBoundedVariableDelaysAndWordPauses() {
        HumanInteractionPolicy policy = policy(7L);
        List<Long> delays = policy.typingDelaysMillis("ab cd");
        assertEquals(5, delays.size());
        for (int index = 0; index < delays.size(); index++) {
            boolean afterWord = index > 0 && "ab cd".charAt(index - 1) == ' ';
            if (afterWord) {
                assertTrue("word boundary pauses are longer", delays.get(index) >= 120);
            } else {
                assertTrue(delays.get(index) >= 40 && delays.get(index) <= 160);
            }
        }
        assertTrue("pre-submit pause is the longest class",
                policy.preSubmitPauseMillis() >= 350);
    }

    @Test
    public void scrollingIsSteppedAndSumsExactly() {
        List<HumanInteractionPolicy.ScrollStep> steps = policy(3L).scrollSteps(1500);
        assertTrue("several steps, never one jump", steps.size() >= 3);
        int sum = 0;
        for (HumanInteractionPolicy.ScrollStep step : steps) {
            assertTrue(step.deltaY > 0);
            sum += step.deltaY;
        }
        assertEquals("the total distance is exact", 1500, sum);
    }

    @Test
    public void thePointerExecutorEmitsTheHumanEventSequence() {
        final List<String> events = new ArrayList<String>();
        HumanPointer pointer = new HumanPointer(policy(5L), new HumanPointer.PointerDevice() {
            public void moveTo(int x, int y) {
                events.add("move " + x + "," + y);
            }

            public void click(int x, int y) {
                events.add("click " + x + "," + y);
            }

            public void wheel(int deltaY) {
                events.add("wheel " + deltaY);
            }

            public void sleep(long millis) {
                events.add("sleep");
            }
        });
        pointer.moveAndClick(0, 0, 240, 120);

        int moves = 0;
        int clickIndex = -1;
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).startsWith("move ")) {
                moves++;
            }
            if (events.get(index).startsWith("click ")) {
                clickIndex = index;
            }
        }
        assertTrue("many move events before the click", moves >= 8);
        assertTrue("exactly one click", clickIndex >= 0);
        assertEquals("the click hits the exact target", "click 240,120",
                events.get(clickIndex));
        assertTrue("a reaction pause right before the click",
                events.get(clickIndex - 1).equals("sleep"));
        assertTrue("a settle pause after the click",
                clickIndex + 1 < events.size() && events.get(clickIndex + 1).equals("sleep"));
    }

    @Test
    public void idleWiggleIsOffByDefaultBoundedWhenEnabledAndNeverAClick() {
        assertTrue("off by default — no surprise pointer noise",
                policy(1L).idleWiggle(100, 100).isEmpty());

        java.util.Map<String, String> env = new java.util.HashMap<String, String>();
        env.put("ASKAI_HUMAN_IDLE_WIGGLE", "true");
        HumanInteractionPolicy enabled = new HumanInteractionPolicy(
                HumanInteractionPolicy.Config.fromEnvironment(env), new Random(4L));
        List<HumanInteractionPolicy.TimedPoint> wiggle = enabled.idleWiggle(200, 300);
        assertTrue(wiggle.size() >= 3);
        for (HumanInteractionPolicy.TimedPoint point : wiggle) {
            assertTrue("stays NEAR the safe position (never travels onto a control)",
                    Math.abs(point.x - 200) <= 14 && Math.abs(point.y - 300) <= 14);
        }
        HumanInteractionPolicy.TimedPoint last = wiggle.get(wiggle.size() - 1);
        assertEquals("ends back where it started", 200, last.x);
        assertEquals(300, last.y);
    }

    private static boolean sameCurve(List<HumanInteractionPolicy.TimedPoint> a,
                                     List<HumanInteractionPolicy.TimedPoint> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int index = 0; index < a.size(); index++) {
            if (a.get(index).x != b.get(index).x || a.get(index).y != b.get(index).y
                    || a.get(index).pauseMillis != b.get(index).pauseMillis) {
                return false;
            }
        }
        return true;
    }
}
