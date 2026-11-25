package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import java.util.function.Consumer;

/** Provide a complete inventory in one traversal without retaining a cursor session. */
public interface PartitionInventorySource {
    /** Emit metadata only; propagate failures and cancellation before declaring the inventory complete. */
    void visitInventory(String partitionKey, Runnable checkpoint, Consumer<KnowledgeResource> found)
            throws KnowledgeSourceException;
}
