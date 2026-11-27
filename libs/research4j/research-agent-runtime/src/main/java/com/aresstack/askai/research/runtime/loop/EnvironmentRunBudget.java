package com.aresstack.askai.research.runtime.loop;

import java.util.Map;

/**
 * Builds the {@link ResearchRunBudget} from the HOST's settings, handed to the agent process as environment
 * variables at launch (the same hand-off as the language and the prompt toggles). The completion target and
 * every safety limit are the USER's configuration — {@link ResearchRunBudget#defaults()} only fills what is
 * unset or invalid, so a missing variable never breaks a session and a nonsense value never becomes a limit.
 */
public final class EnvironmentRunBudget {

    public static final String ENV_TARGET_SOURCES = "ASKAI_SEARCH_TARGET_SOURCES";
    public static final String ENV_MAX_PAGES = "ASKAI_SEARCH_MAX_PAGES";
    public static final String ENV_MAX_TOOL_CALLS = "ASKAI_SEARCH_MAX_TOOL_CALLS";
    public static final String ENV_MAX_MINUTES = "ASKAI_SEARCH_MAX_MINUTES";
    public static final String ENV_MAX_ERRORS = "ASKAI_SEARCH_MAX_ERRORS";

    private EnvironmentRunBudget() {
    }

    /** The configured budget for one search run (call with {@code System.getenv()} productively). */
    public static ResearchRunBudget from(Map<String, String> environment) {
        ResearchRunBudget fallback = ResearchRunBudget.defaults();
        return new ResearchRunBudget(
                positive(environment, ENV_MAX_TOOL_CALLS, fallback.getMaxToolCalls()),
                positive(environment, ENV_MAX_PAGES, fallback.getMaxPagesVisited()),
                positive(environment, ENV_TARGET_SOURCES, fallback.getMaxAcceptedSources()),
                positive(environment, ENV_MAX_ERRORS, fallback.getMaxConsecutiveErrors()),
                positive(environment, ENV_MAX_MINUTES,
                        (int) (fallback.getMaxDurationMillis() / 60_000L)) * 60_000L,
                fallback.getMinimumAcceptedSources(),
                fallback.getMinimumDistinctHosts());
    }

    private static int positive(Map<String, String> environment, String key, int fallback) {
        String raw = environment == null ? null : environment.get(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
