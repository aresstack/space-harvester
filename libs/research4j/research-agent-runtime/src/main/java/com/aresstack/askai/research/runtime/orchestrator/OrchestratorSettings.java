package com.aresstack.askai.research.runtime.orchestrator;

import java.util.Map;

/**
 * The limits of the orchestrated research run (ADR 0001, 4.2 envelope; driver 5: limits are settings, never
 * constants). The host hands them to the agent as {@code ASKAI_ORCHESTRATOR_*} values of the session
 * environment, exactly like the search-run limits; {@link #defaults()} only fills what is unset or invalid.
 *
 * <ul>
 *   <li><b>mode</b>: {@code loop} keeps today's single autonomous research loop for a research question;
 *       {@code orchestrated} hands it to the {@link ResearchOrchestrator} (plan, approval, rounds);</li>
 *   <li><b>maxRounds</b>: evaluation rounds of one run before the run goes back to the user;</li>
 *   <li><b>maxTasks</b>: work packages of one approved run (the initial plan and every follow-up);</li>
 *   <li><b>maxTasksPerRound</b>: how many work packages one orchestrator decision may propose;</li>
 *   <li><b>tokenBudget</b> and <b>timeBudgetMinutes</b>: the approved run budget (0 = not limited);</li>
 *   <li><b>taskRetryLimit</b>, <b>taskTimeMinutes</b>, <b>taskTokenLimit</b>: the per-task limits;</li>
 *   <li><b>minSourcesPerSection</b>: the deterministic coverage rule per outline section;</li>
 *   <li><b>browserSlots</b>: concurrent browser-bound tasks (one browser generation per session today);</li>
 *   <li><b>decisionRepairAttempts</b>: how often an invalid orchestrator answer is sent back once more;</li>
 *   <li><b>extensionRounds</b>, <b>extensionTasks</b>: what one user approval of an overrun adds;</li>
 *   <li><b>evidenceChars</b>: how much of an accepted page a web worker keeps as evidence for the evaluation.</li>
 * </ul>
 */
public final class OrchestratorSettings {

    public static final String ENV_MODE = "ASKAI_ORCHESTRATOR_MODE";
    public static final String ENV_MAX_ROUNDS = "ASKAI_ORCHESTRATOR_MAX_ROUNDS";
    public static final String ENV_MAX_TASKS = "ASKAI_ORCHESTRATOR_MAX_TASKS";
    public static final String ENV_MAX_TASKS_PER_ROUND = "ASKAI_ORCHESTRATOR_MAX_TASKS_PER_ROUND";
    public static final String ENV_TOKEN_BUDGET = "ASKAI_ORCHESTRATOR_TOKEN_BUDGET";
    public static final String ENV_TIME_BUDGET_MINUTES = "ASKAI_ORCHESTRATOR_TIME_BUDGET_MINUTES";
    public static final String ENV_TASK_RETRY_LIMIT = "ASKAI_ORCHESTRATOR_TASK_RETRY_LIMIT";
    public static final String ENV_TASK_TIME_MINUTES = "ASKAI_ORCHESTRATOR_TASK_TIME_MINUTES";
    public static final String ENV_TASK_TOKEN_LIMIT = "ASKAI_ORCHESTRATOR_TASK_TOKEN_LIMIT";
    public static final String ENV_MIN_SOURCES_PER_SECTION = "ASKAI_ORCHESTRATOR_MIN_SOURCES_PER_SECTION";
    public static final String ENV_BROWSER_SLOTS = "ASKAI_ORCHESTRATOR_BROWSER_SLOTS";
    public static final String ENV_DECISION_REPAIR_ATTEMPTS = "ASKAI_ORCHESTRATOR_DECISION_REPAIR_ATTEMPTS";
    public static final String ENV_EXTENSION_ROUNDS = "ASKAI_ORCHESTRATOR_EXTENSION_ROUNDS";
    public static final String ENV_EXTENSION_TASKS = "ASKAI_ORCHESTRATOR_EXTENSION_TASKS";
    public static final String ENV_EVIDENCE_CHARS = "ASKAI_ORCHESTRATOR_EVIDENCE_CHARS";

    /** How a research question in RESEARCH/running is handled. */
    public enum Mode {
        /** Today's single autonomous research loop (the default until the benchmark says otherwise). */
        LOOP("loop"),
        /** The orchestrated run: plan, user approval, rounds of delegated work packages. */
        ORCHESTRATED("orchestrated");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** Unknown or empty values select {@link #LOOP}: a broken setting never switches the agent. */
        public static Mode fromWireName(String value) {
            if (value != null) {
                for (Mode mode : values()) {
                    if (mode.wireName.equalsIgnoreCase(value.trim()) || mode.name().equalsIgnoreCase(value.trim())) {
                        return mode;
                    }
                }
            }
            return LOOP;
        }
    }

    public static final int DEFAULT_MAX_ROUNDS = 4;
    public static final int DEFAULT_MAX_TASKS = 12;
    public static final int DEFAULT_MAX_TASKS_PER_ROUND = 4;
    public static final long DEFAULT_TOKEN_BUDGET = 200_000L;
    public static final int DEFAULT_TIME_BUDGET_MINUTES = 30;
    public static final int DEFAULT_TASK_RETRY_LIMIT = 1;
    public static final int DEFAULT_TASK_TIME_MINUTES = 10;
    public static final long DEFAULT_TASK_TOKEN_LIMIT = 0L;
    public static final int DEFAULT_MIN_SOURCES_PER_SECTION = 1;
    public static final int DEFAULT_BROWSER_SLOTS = 1;
    public static final int DEFAULT_DECISION_REPAIR_ATTEMPTS = 1;
    public static final int DEFAULT_EXTENSION_ROUNDS = 2;
    public static final int DEFAULT_EXTENSION_TASKS = 4;
    public static final int DEFAULT_EVIDENCE_CHARS = 600;

    private final Mode mode;
    private final int maxRounds;
    private final int maxTasks;
    private final int maxTasksPerRound;
    private final long tokenBudget;
    private final int timeBudgetMinutes;
    private final int taskRetryLimit;
    private final int taskTimeMinutes;
    private final long taskTokenLimit;
    private final int minSourcesPerSection;
    private final int browserSlots;
    private final int decisionRepairAttempts;
    private final int extensionRounds;
    private final int extensionTasks;
    private final int evidenceChars;

    private OrchestratorSettings(Builder b) {
        this.mode = b.mode;
        this.maxRounds = b.maxRounds;
        this.maxTasks = b.maxTasks;
        this.maxTasksPerRound = b.maxTasksPerRound;
        this.tokenBudget = b.tokenBudget;
        this.timeBudgetMinutes = b.timeBudgetMinutes;
        this.taskRetryLimit = b.taskRetryLimit;
        this.taskTimeMinutes = b.taskTimeMinutes;
        this.taskTokenLimit = b.taskTokenLimit;
        this.minSourcesPerSection = b.minSourcesPerSection;
        this.browserSlots = b.browserSlots;
        this.decisionRepairAttempts = b.decisionRepairAttempts;
        this.extensionRounds = b.extensionRounds;
        this.extensionTasks = b.extensionTasks;
        this.evidenceChars = b.evidenceChars;
    }

    public static OrchestratorSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The settings from the session environment (call with the host's hand-off map productively). */
    public static OrchestratorSettings from(Map<String, String> environment) {
        Builder b = builder();
        b.mode(Mode.fromWireName(value(environment, ENV_MODE)));
        b.maxRounds(positive(environment, ENV_MAX_ROUNDS, DEFAULT_MAX_ROUNDS));
        b.maxTasks(positive(environment, ENV_MAX_TASKS, DEFAULT_MAX_TASKS));
        b.maxTasksPerRound(positive(environment, ENV_MAX_TASKS_PER_ROUND, DEFAULT_MAX_TASKS_PER_ROUND));
        b.tokenBudget(nonNegativeLong(environment, ENV_TOKEN_BUDGET, DEFAULT_TOKEN_BUDGET));
        b.timeBudgetMinutes((int) nonNegativeLong(environment, ENV_TIME_BUDGET_MINUTES,
                DEFAULT_TIME_BUDGET_MINUTES));
        b.taskRetryLimit((int) nonNegativeLong(environment, ENV_TASK_RETRY_LIMIT, DEFAULT_TASK_RETRY_LIMIT));
        b.taskTimeMinutes((int) nonNegativeLong(environment, ENV_TASK_TIME_MINUTES, DEFAULT_TASK_TIME_MINUTES));
        b.taskTokenLimit(nonNegativeLong(environment, ENV_TASK_TOKEN_LIMIT, DEFAULT_TASK_TOKEN_LIMIT));
        b.minSourcesPerSection(positive(environment, ENV_MIN_SOURCES_PER_SECTION,
                DEFAULT_MIN_SOURCES_PER_SECTION));
        b.browserSlots(positive(environment, ENV_BROWSER_SLOTS, DEFAULT_BROWSER_SLOTS));
        b.decisionRepairAttempts((int) nonNegativeLong(environment, ENV_DECISION_REPAIR_ATTEMPTS,
                DEFAULT_DECISION_REPAIR_ATTEMPTS));
        b.extensionRounds(positive(environment, ENV_EXTENSION_ROUNDS, DEFAULT_EXTENSION_ROUNDS));
        b.extensionTasks(positive(environment, ENV_EXTENSION_TASKS, DEFAULT_EXTENSION_TASKS));
        b.evidenceChars(positive(environment, ENV_EVIDENCE_CHARS, DEFAULT_EVIDENCE_CHARS));
        return b.build();
    }

    public Mode getMode() {
        return mode;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public int getMaxTasks() {
        return maxTasks;
    }

    public int getMaxTasksPerRound() {
        return maxTasksPerRound;
    }

    /** 0 = the run's tokens are not limited. */
    public long getTokenBudget() {
        return tokenBudget;
    }

    /** 0 = the run's wall time is not limited. */
    public int getTimeBudgetMinutes() {
        return timeBudgetMinutes;
    }

    public int getTaskRetryLimit() {
        return taskRetryLimit;
    }

    /** 0 = a task's wall time is not limited. */
    public int getTaskTimeMinutes() {
        return taskTimeMinutes;
    }

    /** 0 = a task's tokens are not limited. */
    public long getTaskTokenLimit() {
        return taskTokenLimit;
    }

    public int getMinSourcesPerSection() {
        return minSourcesPerSection;
    }

    public int getBrowserSlots() {
        return browserSlots;
    }

    public int getDecisionRepairAttempts() {
        return decisionRepairAttempts;
    }

    public int getExtensionRounds() {
        return extensionRounds;
    }

    public int getExtensionTasks() {
        return extensionTasks;
    }

    public int getEvidenceChars() {
        return evidenceChars;
    }

    private static String value(Map<String, String> environment, String key) {
        return environment == null ? null : environment.get(key);
    }

    private static int positive(Map<String, String> environment, String key, int fallback) {
        long value = nonNegativeLong(environment, key, fallback);
        return value > 0 && value <= Integer.MAX_VALUE ? (int) value : fallback;
    }

    private static long nonNegativeLong(Map<String, String> environment, String key, long fallback) {
        String raw = value(environment, key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /** Builds settings; every value starts at its default. */
    public static final class Builder {
        private Mode mode = Mode.LOOP;
        private int maxRounds = DEFAULT_MAX_ROUNDS;
        private int maxTasks = DEFAULT_MAX_TASKS;
        private int maxTasksPerRound = DEFAULT_MAX_TASKS_PER_ROUND;
        private long tokenBudget = DEFAULT_TOKEN_BUDGET;
        private int timeBudgetMinutes = DEFAULT_TIME_BUDGET_MINUTES;
        private int taskRetryLimit = DEFAULT_TASK_RETRY_LIMIT;
        private int taskTimeMinutes = DEFAULT_TASK_TIME_MINUTES;
        private long taskTokenLimit = DEFAULT_TASK_TOKEN_LIMIT;
        private int minSourcesPerSection = DEFAULT_MIN_SOURCES_PER_SECTION;
        private int browserSlots = DEFAULT_BROWSER_SLOTS;
        private int decisionRepairAttempts = DEFAULT_DECISION_REPAIR_ATTEMPTS;
        private int extensionRounds = DEFAULT_EXTENSION_ROUNDS;
        private int extensionTasks = DEFAULT_EXTENSION_TASKS;
        private int evidenceChars = DEFAULT_EVIDENCE_CHARS;

        private Builder() {
        }

        public Builder mode(Mode mode) {
            this.mode = mode == null ? Mode.LOOP : mode;
            return this;
        }

        public Builder maxRounds(int value) {
            this.maxRounds = requirePositive(value, "maxRounds");
            return this;
        }

        public Builder maxTasks(int value) {
            this.maxTasks = requirePositive(value, "maxTasks");
            return this;
        }

        public Builder maxTasksPerRound(int value) {
            this.maxTasksPerRound = requirePositive(value, "maxTasksPerRound");
            return this;
        }

        public Builder tokenBudget(long value) {
            this.tokenBudget = requireNonNegative(value, "tokenBudget");
            return this;
        }

        public Builder timeBudgetMinutes(int value) {
            this.timeBudgetMinutes = (int) requireNonNegative(value, "timeBudgetMinutes");
            return this;
        }

        public Builder taskRetryLimit(int value) {
            this.taskRetryLimit = (int) requireNonNegative(value, "taskRetryLimit");
            return this;
        }

        public Builder taskTimeMinutes(int value) {
            this.taskTimeMinutes = (int) requireNonNegative(value, "taskTimeMinutes");
            return this;
        }

        public Builder taskTokenLimit(long value) {
            this.taskTokenLimit = requireNonNegative(value, "taskTokenLimit");
            return this;
        }

        public Builder minSourcesPerSection(int value) {
            this.minSourcesPerSection = requirePositive(value, "minSourcesPerSection");
            return this;
        }

        public Builder browserSlots(int value) {
            this.browserSlots = requirePositive(value, "browserSlots");
            return this;
        }

        public Builder decisionRepairAttempts(int value) {
            this.decisionRepairAttempts = (int) requireNonNegative(value, "decisionRepairAttempts");
            return this;
        }

        public Builder extensionRounds(int value) {
            this.extensionRounds = requirePositive(value, "extensionRounds");
            return this;
        }

        public Builder extensionTasks(int value) {
            this.extensionTasks = requirePositive(value, "extensionTasks");
            return this;
        }

        public Builder evidenceChars(int value) {
            this.evidenceChars = requirePositive(value, "evidenceChars");
            return this;
        }

        public OrchestratorSettings build() {
            return new OrchestratorSettings(this);
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static long requireNonNegative(long value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }
}
