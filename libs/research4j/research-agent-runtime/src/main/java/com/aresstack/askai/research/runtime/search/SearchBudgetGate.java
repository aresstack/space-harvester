package com.aresstack.askai.research.runtime.search;

/**
 * The neutral budget/cancel gate a {@link SearchStrategy} must consult BEFORE every external call (an MCP
 * tool for the legacy browser path, an HTTP provider call for the API path). It mirrors the research
 * runtime's central {@code beforeToolCall()} gate: {@code true} means "you may spend one call", {@code
 * false} means the budget is exhausted or the run was cancelled and the strategy must stop immediately.
 *
 * <p>Keeping this in the search package (rather than reusing the loop's own tool-budget interface) means a
 * provider strategy never has to depend on the layout-repair client.</p>
 */
public interface SearchBudgetGate {

    /** @return {@code true} when one more external call is permitted; {@code false} to stop now. */
    boolean beforeToolCall();
}
