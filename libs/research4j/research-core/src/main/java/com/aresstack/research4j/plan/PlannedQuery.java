package com.aresstack.research4j.plan;

/**
 * One search the planner wrote for a concept node.
 *
 * @param nodeId the concept node the query serves
 * @param text   the query as a person would type it
 * @param base   whether it is the node's deterministic base query (not written by the model)
 */
public record PlannedQuery(String nodeId, String text, boolean base) {
}
