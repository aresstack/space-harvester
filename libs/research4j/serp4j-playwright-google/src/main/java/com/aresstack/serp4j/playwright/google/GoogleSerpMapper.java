package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.data.PublishedDate;
import com.aresstack.serp4j.core.model.data.ResultProvenance;
import com.aresstack.serp4j.core.model.data.ResultType;
import com.aresstack.serp4j.core.model.data.SearchData;
import com.aresstack.serp4j.core.model.data.SearchResultItem;
import com.aresstack.serp4j.core.model.data.SerpArea;
import com.aresstack.serp4j.core.model.data.SerpPosition;
import com.aresstack.serp4j.core.model.data.SiteLink;
import com.aresstack.serp4j.core.model.ext.RawPayload;
import com.aresstack.serp4j.core.model.ext.RawPayloadPolicy;
import com.aresstack.serp4j.core.model.feature.RelatedQueriesFeature;
import com.aresstack.serp4j.core.model.feature.RelatedQuery;
import com.aresstack.serp4j.core.model.id.ElementId;
import com.aresstack.serp4j.core.model.id.ProviderId;
import com.aresstack.serp4j.core.model.id.SearchEngineId;
import com.aresstack.serp4j.core.model.id.SearchVertical;
import com.aresstack.serp4j.core.model.metadata.ExecutionInfo;
import com.aresstack.serp4j.core.model.metadata.QueryInfo;
import com.aresstack.serp4j.core.model.metadata.SearchMetadata;
import com.aresstack.serp4j.core.model.metadata.SearchWarning;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps a {@link GoogleSerpPage} into the neutral {@link SearchResponse} (engine {@code google}, vertical
 * {@code web}). Shared by the HTTP and the browser provider so both deliver identical {@code SearchData} (ADR-018).
 * Thread-safe.
 */
public final class GoogleSerpMapper {

    /** Warning: result blocks were present that are not mapped yet (SERP features). */
    public static final String WARNING_UNMAPPED_BLOCKS = SearchWarning.UNKNOWN_ELEMENT;

    private final ProviderId provider;

    public GoogleSerpMapper(ProviderId provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * @param page      parse result
     * @param request   the request (limit, query, raw payload policy)
     * @param sourceUrl search URL that was loaded (never contains secrets)
     * @param html      HTML for {@link RawPayload}; may be {@code null}
     * @param warnings  warnings collected before mapping (ignored features, overlays); copied
     */
    public SearchResponse map(GoogleSerpPage page, SearchRequest request, String sourceUrl, String html,
                              Instant startedAt, Instant finishedAt, List<SearchWarning> warnings) {
        SearchData.Builder data = SearchData.builder();
        int limit = request.getPaging().getLimit();
        int offset = request.getPaging().getOffset();
        int count = 0;
        for (GoogleSerpPage.Organic hit : page.getOrganicResults()) {
            if (count >= limit) {
                break;
            }
            count++;
            data.result(item(hit, offset));
        }
        if (!page.getRelatedQueries().isEmpty()) {
            RelatedQueriesFeature.Builder related = RelatedQueriesFeature.builder(ElementId.of(provider.getCode()
                    + "-related"))
                    .position(SerpPosition.builder().area(SerpArea.BOTTOM).build())
                    .provenance(ResultProvenance.builder(provider, SearchEngineId.GOOGLE).build());
            for (GoogleSerpPage.Link query : page.getRelatedQueries()) {
                related.query(RelatedQuery.of(query.getText(), query.getUrl()));
            }
            data.feature(related.build());
        }
        List<SearchWarning> all = new ArrayList<SearchWarning>(warnings);
        if (page.getUnrecognizedBlocks() > 0) {
            all.add(SearchWarning.of(WARNING_UNMAPPED_BLOCKS, page.getUnrecognizedBlocks()
                    + " result block(s) on the Google page are not mapped yet", provider));
        }
        boolean complete = true;
        for (SearchWarning warning : all) {
            if (SearchWarning.PARTIAL_RESULTS.equals(warning.getCode())) {
                complete = false;
            }
        }
        Duration duration = finishedAt.isBefore(startedAt) ? Duration.ZERO : Duration.between(startedAt, finishedAt);
        SearchMetadata.Builder metadata = SearchMetadata
                .forProvider(provider, SearchEngineId.GOOGLE, SearchVertical.WEB, QueryInfo.of(request.getQuery()))
                .execution(ExecutionInfo.builder().startedAt(startedAt).duration(duration).attempts(1)
                        .sourceUrl(sourceUrl).build())
                .complete(complete)
                .warnings(all);
        if (request.getLocale().isPresent()) {
            metadata.locale(request.getLocale().get());
        }
        RawPayloadPolicy policy = request.getRawPayloadPolicy();
        boolean keepRaw = policy == RawPayloadPolicy.ALWAYS || (policy == RawPayloadPolicy.ON_ERROR && (!all.isEmpty() || !complete));
        if (keepRaw && html != null) {
            metadata.rawPayload(RawPayload.ofText("text/html; charset=UTF-8", html));
        }
        return SearchResponse.of(data.build(), metadata.build());
    }

    private SearchResultItem item(GoogleSerpPage.Organic hit, int offset) {
        SearchResultItem.Builder item = SearchResultItem.builder(
                        ElementId.of(provider.getCode() + "-" + (offset + hit.getRank())),
                        hit.isVideo() ? ResultType.VIDEO : ResultType.ORGANIC, hit.getUrl())
                .title(hit.getTitle())
                .position(SerpPosition.builder().rankInGroup(hit.getRank()).rankAbsolute(offset + hit.getRank())
                        .area(SerpArea.MAIN).build())
                .provenance(ResultProvenance.builder(provider, SearchEngineId.GOOGLE).build());
        if (hit.getSnippet() != null) {
            item.snippet(hit.getSnippet());
        }
        if (hit.getDisplayUrl() != null) {
            item.displayUrl(hit.getDisplayUrl());
        }
        if (hit.getSiteName() != null) {
            item.siteName(hit.getSiteName());
        }
        if (hit.getDateText() != null) {
            item.publishedDate(PublishedDate.ofText(hit.getDateText()));
        }
        String domain = directDomain(hit.getUrl());
        if (domain != null) {
            item.domain(domain);
        }
        for (GoogleSerpPage.Link link : hit.getSiteLinks()) {
            item.siteLink(SiteLink.of(link.getUrl(), link.getText(), link.getDescription()));
        }
        return item.build();
    }

    /** Domain of a direct target URL; {@code null} for Google redirects (never guessed from the breadcrumb). */
    static String directDomain(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) {
                return null;
            }
            String h = host.toLowerCase(Locale.ROOT);
            if (h.equals("google.com") || h.endsWith(".google.com")) {
                return null;
            }
            return h;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "GoogleSerpMapper{" + provider + '}';
    }
}
