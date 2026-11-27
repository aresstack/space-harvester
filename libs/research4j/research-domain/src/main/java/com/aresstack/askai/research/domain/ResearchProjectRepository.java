package com.aresstack.askai.research.domain;

/**
 * Persistence PORT for the aggregate: the project directory stays the source of truth; Lucene, embedding
 * and cluster indexes are always rebuildable derived views. File adapters live outside the domain and
 * follow in their own slice — the domain defines only what operations need.
 */
public interface ResearchProjectRepository {

    ResearchProject load(String projectId);

    void save(ResearchProject project);

    /**
     * The captures of {@code sourceId} that a newer capture of the same source replaced (one active capture per
     * source, the newest wins). Their passages are no longer active, so rebuildable views drop them too. An
     * adapter without capture history has none.
     */
    default java.util.Set<String> supersededCaptureIds(String sourceId) {
        return java.util.Collections.emptySet();
    }
}
