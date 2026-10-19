package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;

import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MediaWiki als {@link SearchableKnowledgeSource}.
 *
 * <ul>
 *   <li>Startpunkte im {@link SourceScope} sind Seitentitel; {@code discover} crawlt per Breitensuche über
 *       Seitenlinks bis {@code maxDepth}/{@code maxResources}.</li>
 *   <li>Resource-IDs: {@code wiki:<siteKey>/<Titel>} (MediaWiki-normalisiert, prozentkodiert).</li>
 *   <li>Revision: Zeitpunkt {@code touched} und Revisions-ID ({@code lastrevid}) der Seite.</li>
 *   <li>Inhalt: gerendertes HTML ({@code action=parse}), in Klartext mit Markdown-Überschriften überführt.</li>
 *   <li>Weiterleitungen werden aufgelöst; Dokumente und Links tragen die ID der Zielseite.</li>
 * </ul>
 *
 * <p>Anmeldedaten fordert der Adapter erst beim Login über den {@link MediaWikiCredentialsProvider} an.
 *
 * <p>Netz: Die Composition Root gibt die Proxy-Route ({@link HttpRoutePort}) und die Vertrauensquellen
 * ({@link SSLSocketFactory}) mit; beides wird jeder Verbindung einzeln mitgegeben. Der Konstruktor ohne
 * diese Parameter bleibt für die JVM-Standards erhalten.
 */
public final class MediaWikiKnowledgeSource implements SearchableKnowledgeSource {

    static final String CONTENT_TYPE = "text/html";

    private final SourceAddress sourceId;
    private final MediaWikiSiteConfig site;
    private final MediaWikiClient client;
    private final WikiCrawler crawler;

    public MediaWikiKnowledgeSource(SourceAddress sourceId, MediaWikiSiteConfig site,
                                    MediaWikiCredentialsProvider credentialsProvider) {
        this(sourceId, site, new UrlConnectionMediaWikiTransport(requireSite(site)), credentialsProvider);
    }

    /**
     * @param routes           Proxy-Route je Ziel; {@code null} = JVM-Standard
     * @param sslSocketFactory Vertrauensquellen für HTTPS; {@code null} = JVM-Standard
     */
    public MediaWikiKnowledgeSource(SourceAddress sourceId, MediaWikiSiteConfig site,
                                    MediaWikiCredentialsProvider credentialsProvider, HttpRoutePort routes,
                                    SSLSocketFactory sslSocketFactory) {
        this(sourceId, site, new UrlConnectionMediaWikiTransport(requireSite(site), routes, sslSocketFactory),
                credentialsProvider);
    }

    MediaWikiKnowledgeSource(SourceAddress sourceId, MediaWikiSiteConfig site, MediaWikiTransport transport,
                             MediaWikiCredentialsProvider credentialsProvider) {
        if (sourceId == null) {
            throw new IllegalArgumentException("sourceId must not be null");
        }
        if (credentialsProvider == null) {
            throw new IllegalArgumentException("credentialsProvider must not be null");
        }
        this.sourceId = sourceId;
        this.site = requireSite(site);
        this.client = new MediaWikiClient(site, transport, credentialsProvider);
        this.crawler = new WikiCrawler(client);
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        List<KnowledgeResource> resources = new ArrayList<KnowledgeResource>();
        for (WikiCrawler.CrawledPage page :
                crawler.crawl(scope.startPoints(), scope.maxDepth(), scope.maxResources())) {
            resources.add(resource(page.info(), page.info().revisionId(), page.parentTitle()));
        }
        return resources;
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        WikiPageInfo info = existingPage(resourceId);
        WikiParsedPage parsed = client.parse(info.title());
        long revision = parsed.revisionId() > 0 ? parsed.revisionId() : info.revisionId();
        KnowledgeResource resource = resource(info, revision, null);
        String body = WikiHtmlText.toText(parsed.html());
        String text = body.isEmpty() ? "# " + resource.title() : "# " + resource.title() + "\n\n" + body;
        return KnowledgeDocument.of(resource, text, WikiHtmlText.toBlocks(resource.title(), parsed.html()));
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        WikiPageInfo info = existingPage(resourceId);
        List<String> titles = new ArrayList<String>(new LinkedHashSet<String>(client.outgoingLinks(info.title())));
        if (titles.isEmpty()) {
            return Collections.emptyList();
        }
        // Linkziele auflösen: Weiterleitungen auf die Zielseite, fehlende Seiten (Rotlinks) weglassen.
        Map<KnowledgeResourceId, SourceLink> links = new LinkedHashMap<KnowledgeResourceId, SourceLink>();
        for (WikiPageInfo target : client.pageInfos(titles)) {
            if (target.missing()) {
                continue;
            }
            KnowledgeResourceId id = idOf(target.title());
            if (!links.containsKey(id) && !id.equals(resourceId)) {
                links.put(id, new SourceLink(id, target.title()));
            }
        }
        return new ArrayList<SourceLink>(links.values());
    }

    @Override
    public List<SourceSearchHit> search(SourceQuery query) throws KnowledgeSourceException {
        List<SourceSearchHit> hits = new ArrayList<SourceSearchHit>();
        for (WikiSearchHit hit : client.search(query.text(), query.limit())) {
            if (hits.size() >= query.limit()) {
                break;
            }
            hits.add(new SourceSearchHit(idOf(hit.title()), hit.title(), hit.snippet()));
        }
        return hits;
    }

    @Override
    public String toString() {
        return "MediaWikiKnowledgeSource{" + sourceId + ", " + site + "}";
    }

    private WikiPageInfo existingPage(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String title = WikiResourceIds.titleOf(site.siteKey(), resourceId.value());
        if (title == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED,
                    resourceId + " does not belong to MediaWiki source " + sourceId);
        }
        List<WikiPageInfo> infos = client.pageInfos(Collections.singletonList(title));
        if (infos.isEmpty() || infos.get(0).missing()) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, resourceId + " not found");
        }
        return infos.get(0);
    }

    private KnowledgeResource resource(WikiPageInfo info, long revisionId, String parentTitle) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("wiki.site", site.siteKey());
        metadata.put("wiki.namespace", String.valueOf(info.namespace()));
        if (info.pageId() > 0) {
            metadata.put("wiki.pageId", String.valueOf(info.pageId()));
        }
        if (info.contentModel() != null) {
            metadata.put("wiki.contentModel", info.contentModel());
        }
        return KnowledgeResource.builder(idOf(info.title()), sourceId)
                .title(info.title())
                .contentType(CONTENT_TYPE)
                .revision(KnowledgeRevision.of(instant(info.touched()), revisionId > 0 ? String.valueOf(revisionId) : null))
                .parentId(parentTitle == null ? null : idOf(parentTitle))
                .scope(site.siteKey())
                .location(location(info.fullUrl()))
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private KnowledgeResourceId idOf(String title) {
        return KnowledgeResourceId.of(WikiResourceIds.idFor(site.siteKey(), title));
    }

    private static Instant instant(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static URI location(String fullUrl) {
        if (fullUrl == null || fullUrl.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(fullUrl);
            return uri.getRawUserInfo() == null ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static MediaWikiSiteConfig requireSite(MediaWikiSiteConfig site) {
        if (site == null) {
            throw new IllegalArgumentException("site must not be null");
        }
        return site;
    }
}
