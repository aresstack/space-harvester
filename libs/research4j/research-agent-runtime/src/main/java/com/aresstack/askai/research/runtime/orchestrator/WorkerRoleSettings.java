package com.aresstack.askai.research.runtime.orchestrator;

import java.util.Locale;
import java.util.Map;

/**
 * The settings of the lane G worker roles (ADR 0001, 4.1; driver 5: limits are settings, never constants). The
 * host hands them to the agent as {@code ASKAI_WORKER_*} values of the session environment, next to the
 * {@code ASKAI_ORCHESTRATOR_*} values; unset or invalid values fall back to the defaults.
 *
 * <ul>
 *   <li><b>searchPlanner</b>, <b>sourceVerifier</b>, <b>writer</b>: whether the orchestrator may delegate to
 *       the role at all (a role without a worker is invisible to the orchestrator). Each can be switched off
 *       on its own, which is also how the benchmark measures every role separately;</li>
 *   <li><b>plannerMaxQueries</b>: search queries one planner package may propose;</li>
 *   <li><b>verifierMaxSources</b>: sources one verifier package judges (the rest stay unverified);</li>
 *   <li><b>writerMaxSources</b>: sources one writer package writes from;</li>
 *   <li><b>writerMaxOutputTokens</b>: the output budget of one writer answer (0 = the agent's model-turn
 *       budget, the same one the orchestrator uses);</li>
 *   <li><b>repairAttempts</b>: how often an invalid structured answer is sent back with the reason.</li>
 * </ul>
 */
public final class WorkerRoleSettings {

    public static final String ENV_SEARCH_PLANNER = "ASKAI_WORKER_SEARCH_PLANNER";
    public static final String ENV_SOURCE_VERIFIER = "ASKAI_WORKER_SOURCE_VERIFIER";
    public static final String ENV_WRITER = "ASKAI_WORKER_WRITER";
    public static final String ENV_PLANNER_MAX_QUERIES = "ASKAI_WORKER_PLANNER_MAX_QUERIES";
    public static final String ENV_VERIFIER_MAX_SOURCES = "ASKAI_WORKER_VERIFIER_MAX_SOURCES";
    public static final String ENV_WRITER_MAX_SOURCES = "ASKAI_WORKER_WRITER_MAX_SOURCES";
    public static final String ENV_WRITER_MAX_OUTPUT_TOKENS = "ASKAI_WORKER_WRITER_MAX_OUTPUT_TOKENS";
    public static final String ENV_REPAIR_ATTEMPTS = "ASKAI_WORKER_REPAIR_ATTEMPTS";

    public static final boolean DEFAULT_SEARCH_PLANNER = true;
    public static final boolean DEFAULT_SOURCE_VERIFIER = true;
    public static final boolean DEFAULT_WRITER = true;
    public static final int DEFAULT_PLANNER_MAX_QUERIES = 3;
    public static final int DEFAULT_VERIFIER_MAX_SOURCES = 8;
    public static final int DEFAULT_WRITER_MAX_SOURCES = 8;
    public static final int DEFAULT_WRITER_MAX_OUTPUT_TOKENS = 0;
    public static final int DEFAULT_REPAIR_ATTEMPTS = 1;

    private final boolean searchPlanner;
    private final boolean sourceVerifier;
    private final boolean writer;
    private final int plannerMaxQueries;
    private final int verifierMaxSources;
    private final int writerMaxSources;
    private final int writerMaxOutputTokens;
    private final int repairAttempts;

    public WorkerRoleSettings(boolean searchPlanner, boolean sourceVerifier, boolean writer, int plannerMaxQueries,
                              int verifierMaxSources, int writerMaxSources, int writerMaxOutputTokens,
                              int repairAttempts) {
        this.searchPlanner = searchPlanner;
        this.sourceVerifier = sourceVerifier;
        this.writer = writer;
        this.plannerMaxQueries = plannerMaxQueries > 0 ? plannerMaxQueries : DEFAULT_PLANNER_MAX_QUERIES;
        this.verifierMaxSources = verifierMaxSources > 0 ? verifierMaxSources : DEFAULT_VERIFIER_MAX_SOURCES;
        this.writerMaxSources = writerMaxSources > 0 ? writerMaxSources : DEFAULT_WRITER_MAX_SOURCES;
        this.writerMaxOutputTokens = Math.max(0, writerMaxOutputTokens);
        this.repairAttempts = Math.max(0, repairAttempts);
    }

    public static WorkerRoleSettings defaults() {
        return from(null);
    }

    public static WorkerRoleSettings from(Map<String, String> environment) {
        return new WorkerRoleSettings(
                flag(environment, ENV_SEARCH_PLANNER, DEFAULT_SEARCH_PLANNER),
                flag(environment, ENV_SOURCE_VERIFIER, DEFAULT_SOURCE_VERIFIER),
                flag(environment, ENV_WRITER, DEFAULT_WRITER),
                number(environment, ENV_PLANNER_MAX_QUERIES, DEFAULT_PLANNER_MAX_QUERIES),
                number(environment, ENV_VERIFIER_MAX_SOURCES, DEFAULT_VERIFIER_MAX_SOURCES),
                number(environment, ENV_WRITER_MAX_SOURCES, DEFAULT_WRITER_MAX_SOURCES),
                number(environment, ENV_WRITER_MAX_OUTPUT_TOKENS, DEFAULT_WRITER_MAX_OUTPUT_TOKENS),
                number(environment, ENV_REPAIR_ATTEMPTS, DEFAULT_REPAIR_ATTEMPTS));
    }

    public boolean isSearchPlanner() {
        return searchPlanner;
    }

    public boolean isSourceVerifier() {
        return sourceVerifier;
    }

    public boolean isWriter() {
        return writer;
    }

    public int getPlannerMaxQueries() {
        return plannerMaxQueries;
    }

    public int getVerifierMaxSources() {
        return verifierMaxSources;
    }

    public int getWriterMaxSources() {
        return writerMaxSources;
    }

    /** 0 = the agent's model-turn budget. */
    public int getWriterMaxOutputTokens() {
        return writerMaxOutputTokens;
    }

    public int getRepairAttempts() {
        return repairAttempts;
    }

    /** {@code on/true/yes/1} and {@code off/false/no/0}; anything else keeps the default. */
    private static boolean flag(Map<String, String> environment, String key, boolean fallback) {
        String raw = environment == null ? null : environment.get(key);
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("on") || value.equals("true") || value.equals("yes") || value.equals("1")) {
            return true;
        }
        if (value.equals("off") || value.equals("false") || value.equals("no") || value.equals("0")) {
            return false;
        }
        return fallback;
    }

    private static int number(Map<String, String> environment, String key, int fallback) {
        String raw = environment == null ? null : environment.get(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
