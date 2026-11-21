package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

/**
 * Provide a source-neutral navigation presentation kind for resources.
 * Implement this optional port when pages and directories share a navigation hierarchy.
 */
interface ResourceNodeKindSource {

    HarvestSource.PageNode.Kind kind(KnowledgeResourceId resourceId) throws KnowledgeSourceException;
}
