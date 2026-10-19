package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Breitensuche ab Startseiten über Seitenlinks bis {@code maxDepth} und {@code maxPages} (paketintern).
 *
 * <p>Algorithmus aus MainframeMate {@code WikiSourceScanner.scan}: BFS je Ebene, besuchte Titel merken,
 * Fehler beim Laden der Links einer Seite überspringen statt den Crawl abzubrechen. Neu: Titel werden vor
 * dem Zählen über {@code prop=info} aufgelöst (Normalisierung, Weiterleitungen, fehlende Seiten), damit
 * {@code A_b}, {@code A b} und eine Weiterleitung auf dieselbe Seite nur einmal zählen; jede Seite merkt
 * sich, von welcher Seite sie zuerst erreicht wurde.
 */
final class WikiCrawler {

    private static final Logger LOG = Logger.getLogger(WikiCrawler.class.getName());

    private final MediaWikiClient client;

    WikiCrawler(MediaWikiClient client) {
        this.client = client;
    }

    List<CrawledPage> crawl(List<String> startTitles, int maxDepth, int maxPages) throws KnowledgeSourceException {
        Map<String, CrawledPage> visited = new LinkedHashMap<String, CrawledPage>();
        Map<String, String> level = new LinkedHashMap<String, String>();
        for (String title : startTitles) {
            level.put(title, null);
        }
        for (int depth = 0; depth <= maxDepth && !level.isEmpty() && visited.size() < maxPages; depth++) {
            List<CrawledPage> added = new ArrayList<CrawledPage>();
            for (WikiPageInfo info : client.pageInfos(new ArrayList<String>(level.keySet()))) {
                if (visited.size() >= maxPages) {
                    break;
                }
                String key = WikiResourceIds.canonicalTitle(info.title());
                if (info.missing() || visited.containsKey(key)) {
                    if (info.missing()) {
                        LOG.fine("[MediaWiki] skipping missing page " + info.title());
                    }
                    continue;
                }
                CrawledPage page = new CrawledPage(info, level.get(info.requestedTitle()), depth);
                visited.put(key, page);
                added.add(page);
            }
            Map<String, String> next = new LinkedHashMap<String, String>();
            if (depth < maxDepth && visited.size() < maxPages) {
                for (CrawledPage page : added) {
                    for (String link : linksOf(page.info().title())) {
                        String key = WikiResourceIds.canonicalTitle(link);
                        if (!visited.containsKey(key) && !next.containsKey(link)) {
                            next.put(link, page.info().title());
                        }
                    }
                }
            }
            level = next;
        }
        return new ArrayList<CrawledPage>(visited.values());
    }

    private List<String> linksOf(String title) throws KnowledgeSourceException {
        try {
            return client.outgoingLinks(title);
        } catch (KnowledgeSourceException e) {
            // Zugriff verweigert oder Wiki nicht erreichbar betrifft die ganze Quelle: Crawl abbrechen.
            if (e.kind() == Kind.ACCESS_DENIED || e.kind() == Kind.UNAVAILABLE) {
                throw e;
            }
            LOG.log(Level.WARNING, "[MediaWiki] failed to load links of " + title + ": " + e.getMessage());
            return new ArrayList<String>();
        }
    }

    static final class CrawledPage {

        private final WikiPageInfo info;
        private final String parentTitle;
        private final int depth;

        CrawledPage(WikiPageInfo info, String parentTitle, int depth) {
            this.info = info;
            this.parentTitle = parentTitle;
            this.depth = depth;
        }

        WikiPageInfo info() {
            return info;
        }

        /** Titel der Seite, über die diese zuerst erreicht wurde; {@code null} für Startseiten. */
        String parentTitle() {
            return parentTitle;
        }

        int depth() {
            return depth;
        }
    }
}
