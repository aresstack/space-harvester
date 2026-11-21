package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import java.util.List;

/**
 * Find titles only among metadata already observed by the source.
 * Never start discovery or a remote search from this capability.
 */
interface KnownTitleLookup {

    List<KnowledgeResource> knownTitles(String fragment, int limit);
}
