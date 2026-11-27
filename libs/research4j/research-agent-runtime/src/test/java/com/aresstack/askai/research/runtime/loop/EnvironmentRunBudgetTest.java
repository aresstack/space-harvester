package com.aresstack.askai.research.runtime.loop;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * The run budget is the USER's configuration (settings → env hand-off), never only code constants: every
 * configured value lands in the budget, an unset/nonsense variable falls back to the documented default,
 * and the legacy minimums stay untouched (they belong to the autonomous completion policy's own slice).
 */
public class EnvironmentRunBudgetTest {

    @Test
    public void configuredValuesLandInTheBudget() {
        Map<String, String> env = new HashMap<String, String>();
        env.put(EnvironmentRunBudget.ENV_TARGET_SOURCES, "5");
        env.put(EnvironmentRunBudget.ENV_MAX_PAGES, "40");
        env.put(EnvironmentRunBudget.ENV_MAX_TOOL_CALLS, "60");
        env.put(EnvironmentRunBudget.ENV_MAX_MINUTES, "25");
        env.put(EnvironmentRunBudget.ENV_MAX_ERRORS, "7");
        ResearchRunBudget budget = EnvironmentRunBudget.from(env);
        assertEquals(5, budget.getMaxAcceptedSources());
        assertEquals(40, budget.getMaxPagesVisited());
        assertEquals(60, budget.getMaxToolCalls());
        assertEquals(25L * 60_000L, budget.getMaxDurationMillis());
        assertEquals(7, budget.getMaxConsecutiveErrors());
    }

    @Test
    public void unsetOrNonsenseValuesFallBackToTheDefaults() {
        ResearchRunBudget defaults = ResearchRunBudget.defaults();
        Map<String, String> env = new HashMap<String, String>();
        env.put(EnvironmentRunBudget.ENV_TARGET_SOURCES, "banane");
        env.put(EnvironmentRunBudget.ENV_MAX_PAGES, "0");
        env.put(EnvironmentRunBudget.ENV_MAX_MINUTES, "-3");
        ResearchRunBudget budget = EnvironmentRunBudget.from(env);
        assertEquals(defaults.getMaxAcceptedSources(), budget.getMaxAcceptedSources());
        assertEquals(defaults.getMaxPagesVisited(), budget.getMaxPagesVisited());
        assertEquals(defaults.getMaxToolCalls(), budget.getMaxToolCalls());
        assertEquals(defaults.getMaxDurationMillis(), budget.getMaxDurationMillis());
        assertEquals(defaults.getMaxConsecutiveErrors(), budget.getMaxConsecutiveErrors());

        ResearchRunBudget empty = EnvironmentRunBudget.from(new HashMap<String, String>());
        assertEquals(defaults.getMaxAcceptedSources(), empty.getMaxAcceptedSources());
        assertEquals("legacy minimums stay what they were",
                defaults.getMinimumAcceptedSources(), empty.getMinimumAcceptedSources());
        assertEquals(defaults.getMinimumDistinctHosts(), empty.getMinimumDistinctHosts());
    }
}
