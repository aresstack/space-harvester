package com.aresstack.spaceharvester.application.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test-Fake: merkt sich, was geschrieben, veröffentlicht oder verworfen wurde. */
final class RecordingExportArtifactWriter implements ExportArtifactWriter {

    final Map<String, Published> published = new HashMap<>();
    final List<String> discarded = new ArrayList<>();

    record Published(List<String> pageIds, ExportTree tree, ExportSummary summary) {
    }

    @Override
    public Session begin(String exportId, ExportScope scope) {
        List<String> pageIds = new ArrayList<>();
        return new Session() {
            private boolean done;

            @Override
            public void page(ExportPage page) {
                pageIds.add(page.pageId());
            }

            @Override
            public ExportArtifact publish(ExportTree tree, ExportSummary summary) {
                done = true;
                published.put(exportId, new Published(pageIds, tree, summary));
                return new ExportArtifact(exportId, summary.complete(), List.of());
            }

            @Override
            public void close() {
                if (!done) {
                    discarded.add(exportId);
                }
            }
        };
    }

    @Override
    public Optional<ExportArtifact> find(String exportId) {
        Published p = published.get(exportId);
        return p == null ? Optional.empty()
                : Optional.of(new ExportArtifact(exportId, p.summary().complete(), List.of()));
    }
}
