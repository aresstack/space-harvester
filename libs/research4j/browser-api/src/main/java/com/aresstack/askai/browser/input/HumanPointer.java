package com.aresstack.askai.browser.input;

import java.util.List;

/**
 * #44 — executes a {@link HumanInteractionPolicy} against an abstract pointer device: the
 * SEQUENCE (many timed move events → reaction pause → click → settle pause) lives here and is
 * unit-tested against a fake device; the Playwright driver only implements the four device
 * primitives. Browser code calls {@code moveAndClick}/{@code scrollBy} and never hand-rolls
 * paths, pauses or wheel steps.
 */
public final class HumanPointer {

    /** The narrow device seam a real driver (Playwright page.mouse) implements. */
    public interface PointerDevice {
        void moveTo(int x, int y);

        void click(int x, int y);

        void wheel(int deltaY);

        void sleep(long millis);
    }

    private final HumanInteractionPolicy policy;
    private final PointerDevice device;

    public HumanPointer(HumanInteractionPolicy policy, PointerDevice device) {
        this.policy = policy;
        this.device = device;
    }

    /**
     * Human-paced click: travel to the target over several timed waypoints (never a
     * teleport), pause the reaction time, click EXACTLY the target, settle briefly.
     */
    public void moveAndClick(int fromX, int fromY, int targetX, int targetY) {
        List<HumanInteractionPolicy.TimedPoint> path =
                policy.mousePath(fromX, fromY, targetX, targetY);
        for (HumanInteractionPolicy.TimedPoint point : path) {
            device.moveTo(point.x, point.y);
            device.sleep(point.pauseMillis);
        }
        device.sleep(policy.preClickPauseMillis());
        device.click(targetX, targetY);
        device.sleep(policy.postClickPauseMillis());
    }

    /** Human-paced scrolling: several bounded wheel steps with pauses, never one jump. */
    public void scrollBy(int totalDeltaY) {
        for (HumanInteractionPolicy.ScrollStep step : policy.scrollSteps(totalDeltaY)) {
            device.wheel(step.deltaY);
            device.sleep(step.pauseMillis);
        }
    }
}
