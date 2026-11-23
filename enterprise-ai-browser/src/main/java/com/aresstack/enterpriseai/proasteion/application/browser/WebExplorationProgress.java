package com.aresstack.enterpriseai.proasteion.application.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable snapshot of a chat's web exploration in the running turn. The counts are distinct canonical addresses:
 * <ul>
 *     <li><b>discovered</b>: links found on the pages listed in the turn, also those beyond the list limit;</li>
 *     <li><b>examined</b>: link targets handed to the model to decide on (the links it was shown);</li>
 *     <li><b>visited</b>: pages loaded and returned to the model;</li>
 *     <li><b>skipped</b>: targets not opened on purpose (blocked by the browser policy or the user);</li>
 *     <li><b>failed</b>: targets that could not be loaded or read;</li>
 *     <li><b>relevant</b>: pages the model marked as relevant for its answer, i.e. used.</li>
 * </ul>
 * The steps are the latest events in order, the newest last.
 */
public final class WebExplorationProgress {

    /** What happened in one step of the exploration. */
    public enum StepKind {
        OPENED, LINKS, BACK, ACTED, CHALLENGE, SKIPPED, FAILED, MARKED_RELEVANT, MARKED_IRRELEVANT, CLOSED
    }

    /** One event: kind, a label (page title, host, filter word or error) and a count where the kind has one. */
    public static final class Step {

        private final StepKind kind;
        private final String label;
        private final int count;

        public Step(StepKind kind, String label, int count) {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            this.kind = kind;
            this.label = label == null ? "" : label;
            this.count = count;
        }

        public StepKind kind() {
            return kind;
        }

        public String label() {
            return label;
        }

        /** Links found for {@link StepKind#LINKS}; 0 otherwise. */
        public int count() {
            return count;
        }
    }

    private final boolean active;
    private final boolean running;
    private final int discovered;
    private final int examined;
    private final int visited;
    private final int skipped;
    private final int failed;
    private final int relevant;
    private final List<Step> steps;
    private final String challenge;

    public WebExplorationProgress(boolean active, boolean running, int discovered, int examined, int visited,
                                  int skipped, int failed, int relevant, List<Step> steps) {
        this(active, running, discovered, examined, visited, skipped, failed, relevant, steps, "");
    }

    public WebExplorationProgress(boolean active, boolean running, int discovered, int examined, int visited,
                                  int skipped, int failed, int relevant, List<Step> steps, String challenge) {
        this.challenge = challenge == null ? "" : challenge;
        this.active = active;
        this.running = running;
        this.discovered = discovered;
        this.examined = examined;
        this.visited = visited;
        this.skipped = skipped;
        this.failed = failed;
        this.relevant = relevant;
        this.steps = steps == null ? Collections.<Step>emptyList()
                : Collections.unmodifiableList(new ArrayList<Step>(steps));
    }

    /** Nothing happened yet. */
    public static WebExplorationProgress none() {
        return new WebExplorationProgress(false, false, 0, 0, 0, 0, 0, 0, null);
    }

    /** Whether the turn used the browser at all. */
    public boolean isActive() {
        return active;
    }

    /** Whether a browser tool is executing right now. */
    public boolean isRunning() {
        return running;
    }

    public int discovered() {
        return discovered;
    }

    public int examined() {
        return examined;
    }

    public int visited() {
        return visited;
    }

    public int skipped() {
        return skipped;
    }

    public int failed() {
        return failed;
    }

    public int relevant() {
        return relevant;
    }

    public List<Step> steps() {
        return steps;
    }

    /**
     * The challenge the user still has to solve in the visible browser ({@code captcha}, {@code login}), else empty;
     * while one is pending the browser stays open after the turn.
     */
    public String challenge() {
        return challenge;
    }
}
