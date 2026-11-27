package com.aresstack.askai.research.runtime.team.mailbox;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who a message is from or for: the orchestrator or a worker by the display number of its run (plan T3,
 * section 2.4). A model only ever writes {@code orchestrator} or {@code worker 4}; Java maps the number to the task.
 */
public final class AgentAddress {

    public static final AgentAddress ORCHESTRATOR = new AgentAddress(0);

    private static final Pattern WORKER = Pattern.compile("^(?:worker|w|#)?\\s*#?\\s*(\\d{1,4})$");
    private static final Pattern ORCHESTRATOR_NAMES = Pattern.compile("^(?:the\\s+)?(?:orchestrator|coordinator|lead)$");

    private final int workerNumber;

    private AgentAddress(int workerNumber) {
        this.workerNumber = workerNumber;
    }

    public static AgentAddress worker(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("worker numbers start at 1: " + number);
        }
        return new AgentAddress(number);
    }

    /**
     * Reads what a model wrote as recipient: {@code orchestrator}, {@code Orchestrator}, {@code worker 4},
     * {@code Worker4}, {@code W4}, {@code #4}, {@code 4}. Returns {@code null} for anything else.
     */
    public static AgentAddress parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s:.,;]+$", "").replaceAll("\\s+", " ");
        if (ORCHESTRATOR_NAMES.matcher(t).matches()) {
            return ORCHESTRATOR;
        }
        Matcher m = WORKER.matcher(t);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            return n >= 1 ? worker(n) : null;
        }
        return null;
    }

    public boolean isOrchestrator() {
        return workerNumber == 0;
    }

    /** The display number, 0 for the orchestrator. */
    public int getWorkerNumber() {
        return workerNumber;
    }

    /** The persisted and displayed form: {@code orchestrator} or {@code worker 4}. */
    public String key() {
        return isOrchestrator() ? "orchestrator" : "worker " + workerNumber;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AgentAddress && ((AgentAddress) o).workerNumber == workerNumber;
    }

    @Override
    public int hashCode() {
        return workerNumber;
    }

    @Override
    public String toString() {
        return key();
    }
}
