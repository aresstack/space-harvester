package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Test-Fake: merkt sich, was geschrieben, veröffentlicht oder verworfen wurde. */
final class RecordingExportArtifactWriter implements ExportArtifactWriter {

    final Map<String, Published> published = new HashMap<>();
    final List<String> discarded = new ArrayList<>();
    /** Artefakte im Entstehen, auch Reste, die ein Test als „vom letzten Lauf liegen geblieben“ vorgibt. */
    final Set<String> unpublished = new HashSet<>();

    record Published(List<String> pageIds, PageTree tree, ExportSummary summary) {
    }

    @Override
    public Session begin(String exportId, ExportScope scope) {
        List<String> pageIds = new ArrayList<>();
        unpublished.add(exportId);
        return new Session() {
            private boolean done;

            @Override
            public void page(PageHeader header, PageSnapshot snapshot) {
                pageIds.add(header.key().pageId());
            }

            @Override
            public ExportArtifact publish(PageTree tree, ExportSummary summary) {
                done = true;
                unpublished.remove(exportId);
                published.put(exportId, new Published(pageIds, tree, summary));
                return new ExportArtifact(exportId, summary.complete(), List.of());
            }

            @Override
            public void close() {
                if (!done) {
                    unpublished.remove(exportId);
                    discarded.add(exportId);
                }
            }
        };
    }

    @Override
    public Optional<java.io.InputStream> open(String exportId, String name) {
        return Optional.empty();
    }

    @Override
    public Optional<ExportArtifact> find(String exportId) {
        Published p = published.get(exportId);
        return p == null ? Optional.empty()
                : Optional.of(new ExportArtifact(exportId, p.summary().complete(), List.of()));
    }

    @Override
    public Set<String> published() {
        return Set.copyOf(published.keySet());
    }

    @Override
    public Set<String> unpublished() {
        return Set.copyOf(unpublished);
    }

    @Override
    public void delete(String exportId) {
        published.remove(exportId);
    }

    @Override
    public void discard(String exportId) {
        if (unpublished.remove(exportId)) {
            discarded.add(exportId);
        }
    }
}
