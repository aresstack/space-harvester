package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/** Determine whether an absent resource was within the last successfully enumerated inventory. */
public interface InventoryCoverageSource {

    boolean inventoryCovers(KnowledgeResourceId resourceId);
}