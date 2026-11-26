package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.evidence.CurrentSources;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Current state for the technical check of source-location evidence (ADR 0041, items 10 and 11): content hashes and
 * line counts of stored FTP documents the caller can read, and the identity of the current visible parser result read
 * through {@link ProgramAnalysisAccess}. Unreadable means absent.
 */
public final class ProgramCurrentSources implements CurrentSources {

    private final Function<PageKey, Optional<DeliveredPage>> pages;
    private final ProgramAnalysisAccess access;

    /**
     * @param pages  authorized reads of stored pages for the current caller; empty when not readable
     * @param access the one gate for program analyses
     */
    public ProgramCurrentSources(Function<PageKey, Optional<DeliveredPage>> pages, ProgramAnalysisAccess access) {
        this.pages = Objects.requireNonNull(pages);
        this.access = Objects.requireNonNull(access);
    }

    @Override
    public Optional<Current> document(DocumentKey document) {
        if (!document.isPage()) return Optional.empty();
        return page(document.page()).flatMap(page -> StoredFtpText.read(page.snapshot())
                .map(text -> new Current(page.snapshot().payload().rawSha256(), (int) text.content().lines().count())));
    }

    @Override
    public Optional<Resolved> dependency(DocumentKey program, String name) {
        PageKey key = new PageKey(program.page().sourceId(), name);
        return page(key).map(page -> new Resolved(DocumentKey.page(key), page.snapshot().payload().rawSha256()));
    }

    @Override
    public Optional<String> analysisVersion(DocumentKey program) {
        if (!program.isPage()) return Optional.empty();
        return page(program.page()).flatMap(page -> access.current(page, CobolProgramEnrichment.ID))
                .map(ProgramAnalysisAccess::identity);
    }

    private Optional<DeliveredPage> page(PageKey key) {
        return pages.apply(key).filter(page -> key.equals(page.header().key()));
    }
}
