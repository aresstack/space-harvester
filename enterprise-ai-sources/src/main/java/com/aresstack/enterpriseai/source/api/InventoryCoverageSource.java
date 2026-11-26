package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/** Determine whether an absent resource was within the last successfully enumerated inventory. */
public interface InventoryCoverageSource {

    boolean inventoryCovers(KnowledgeResourceId resourceId);

    /**
     * Whether the resource lies within the configured scope of the last inventory (under one of its start points),
     * regardless of whether that area was enumerated completely. A resource outside has no inventory reason to be kept.
     * Unknown (no inventory yet, or the source cannot tell) means {@code true}, so nothing is treated as outside.
     */
    default boolean withinInventoryScope(KnowledgeResourceId resourceId) {
        return true;
    }
}