package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keep immutable change snapshots for stable, source-bound paging. */
final class SharePointChangePages {
    private static final int MAX_SESSIONS = 4;
    private final Map<String, List<KnowledgeResource>> snapshots = new LinkedHashMap<String, List<KnowledgeResource>>();

    interface Loader {
        List<KnowledgeResource> load() throws KnowledgeSourceException;
    }

    PartitionResources page(String cursor, int limit, Loader loader) throws KnowledgeSourceException {
        if (limit < 1) throw invalid();
        if (cursor == null || cursor.isEmpty()) {
            List<KnowledgeResource> resources = new ArrayList<KnowledgeResource>(loader.load());
            String key = UUID.randomUUID().toString();
            if (resources.size() > limit) {
                synchronized (snapshots) {
                    while (snapshots.size() >= MAX_SESSIONS) snapshots.remove(snapshots.keySet().iterator().next());
                    snapshots.put(key, resources);
                }
            }
            return batch(key, resources, 0, limit);
        }
        String[] parts = cursor.split(":", -1);
        if (parts.length != 2) throw invalid();
        List<KnowledgeResource> resources;
        synchronized (snapshots) { resources = snapshots.get(parts[0]); }
        int start;
        try { start = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { throw invalid(); }
        if (resources == null || start <= 0 || start >= resources.size()) throw invalid();
        return batch(parts[0], resources, start, limit);
    }

    private PartitionResources batch(String key, List<KnowledgeResource> resources, int start, int limit) {
        int end = start + Math.min(limit, resources.size() - start);
        if (end == resources.size()) {
            synchronized (snapshots) { snapshots.remove(key); }
        }
        return new PartitionResources(resources.subList(start, end), end == resources.size() ? "" : key + ":" + end);
    }

    private static KnowledgeSourceException invalid() {
        return new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "SharePoint: ungültiger Änderungscursor");
    }
}
