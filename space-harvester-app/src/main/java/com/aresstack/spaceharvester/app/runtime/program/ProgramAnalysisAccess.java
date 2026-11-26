package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The one gate through which stored program analyses reach the viewer, the program search, the chat tools and any
 * other reader (#1486).
 *
 * <p>An analysis is shown only while it is current and the caller can read every document it was derived from: the
 * program itself and each resolved copybook. Currency is the enrichment freshness, which already covers the program
 * hash, the processor (parser) version, the SYSLIB order and all positive and negative copybook probes
 * ({@link CobolProgramEnrichment#outdated}). Search results remember the exact artifact they were built from
 * ({@link StoredCobolSearch.Origin#analysis()}), so that {@link #current(StoredCobolSearch.Origin)} can reject a hit
 * whose analysis was replaced, invalidated or became unreadable after the search snapshot was taken.
 */
public final class ProgramAnalysisAccess {

    /** Processors whose results are program analyses. */
    public static final Set<String> PROCESSORS = Set.of(CobolProgramEnrichment.ID, CobolBusinessEnrichment.ID,
            JclProgramEnrichment.ID, NaturalProgramEnrichment.ID);

    /** Decides whether a stored business hypothesis still belongs to the current technical analysis. */
    public interface BusinessBasis {
        boolean matches(DeliveredPage page, Enrichment business, List<Enrichments.View> views);
    }

    private final Function<PageKey, Optional<DeliveredPage>> pages;
    private final Function<DeliveredPage, List<Enrichments.View>> stored;
    private final Function<Enrichment, List<PageKey>> evidence;
    private final BusinessBasis business;

    /**
     * @param pages    authorized reads of stored pages for the current caller; empty when not readable
     * @param stored   stored results of a page with their freshness, without any concealment
     * @param evidence other pages a stored result was derived from ({@link Enrichments#evidence})
     * @param business whether a business hypothesis still matches the technical analysis it was made from
     */
    public ProgramAnalysisAccess(Function<PageKey, Optional<DeliveredPage>> pages,
                                 Function<DeliveredPage, List<Enrichments.View>> stored,
                                 Function<Enrichment, List<PageKey>> evidence, BusinessBasis business) {
        this.pages = Objects.requireNonNull(pages);
        this.stored = Objects.requireNonNull(stored);
        this.evidence = Objects.requireNonNull(evidence);
        this.business = Objects.requireNonNull(business);
    }

    /**
     * The stored results of a page as the caller may see them: a program analysis that is not current, or whose
     * evidence the caller cannot read, is replaced by an empty stale result, so neither its facts nor derived text
     * leave this method. Other results are returned unchanged.
     */
    public List<Enrichments.View> visible(DeliveredPage page) {
        List<Enrichments.View> views = stored.apply(page);
        return views.stream().map(view -> visible(page, view, views) ? view : concealed(view)).toList();
    }

    private boolean visible(DeliveredPage page, Enrichments.View view, List<Enrichments.View> views) {
        Enrichment result = view.enrichment();
        if (!PROCESSORS.contains(result.processorId())) return true;
        if (view.freshness() != Enrichment.Freshness.CURRENT || result.status() != Enrichment.Status.OK) return false;
        if (!page.header().key().equals(result.key())) return false;
        if (!evidenceReadable(result)) return false;
        return !CobolBusinessEnrichment.ID.equals(result.processorId()) || business.matches(page, result, views);
    }

    private boolean evidenceReadable(Enrichment result) {
        for (PageKey key : evidence.apply(result)) {
            var document = pages.apply(key);
            if (document.isEmpty() || !key.equals(document.get().header().key())) return false;
        }
        return true;
    }

    private static Enrichments.View concealed(Enrichments.View view) {
        Enrichment previous = view.enrichment();
        var empty = new Enrichment(previous.key(), previous.processorId(), previous.processorVersion(),
                previous.inputSha256(), previous.revision(), Enrichment.Status.OK, "{}", List.of(), null,
                previous.computedAt());
        return new Enrichments.View(empty, Enrichment.Freshness.STALE);
    }

    /** The visible current result of one processor for a page, if any. */
    public Optional<Enrichment> current(DeliveredPage page, String processorId) {
        return visible(page).stream()
                .filter(view -> processorId.equals(view.enrichment().processorId())
                        && view.freshness() == Enrichment.Freshness.CURRENT
                        && view.enrichment().status() == Enrichment.Status.OK)
                .map(Enrichments.View::enrichment).findFirst();
    }

    /**
     * Whether an origin of a search result may still be delivered as current: the document is readable with the same
     * content and, when the origin names an analysis, exactly that analysis is still the visible current one. Fails
     * closed on any doubt.
     */
    public boolean current(StoredCobolSearch.Origin origin) {
        var page = pages.apply(origin.key()).orElse(null);
        if (page == null || !origin.key().equals(page.header().key()) || !origin.key().equals(page.snapshot().key())
                || !origin.rawSha256().equals(page.snapshot().payload().rawSha256())) return false;
        if (origin.analysis() == null) return true;
        String processor = processor(origin.analysis());
        return processor != null && current(page, processor)
                .filter(result -> origin.analysis().equals(identity(result))).isPresent();
    }

    /** Revalidate every origin; true only if all of them are current. */
    public boolean current(java.util.Collection<StoredCobolSearch.Origin> origins) {
        for (var origin : origins) {
            if (!current(origin)) return false;
        }
        return true;
    }

    /**
     * Identity of a stored analysis: processor, processor version and a digest of the exact stored result. Two
     * results with the same identity carry the same facts.
     */
    public static String identity(Enrichment result) {
        return result.processorId() + "@" + result.processorVersion() + "#" + sha256(result.payloadJson());
    }

    private static String processor(String identity) {
        int at = identity.indexOf('@');
        return at > 0 ? identity.substring(0, at) : null;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
