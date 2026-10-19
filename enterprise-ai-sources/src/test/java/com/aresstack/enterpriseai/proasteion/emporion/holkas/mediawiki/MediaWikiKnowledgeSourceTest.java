package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import org.junit.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MediaWikiKnowledgeSourceTest {

    private final FakeMediaWikiTransport wiki = MediaWikiKnowledgeSourceContractTest.sampleWiki();
    private final MediaWikiKnowledgeSource source = new MediaWikiKnowledgeSource(SourceAddress.of("wiki-intranet"),
            MediaWikiSiteConfig.builder("intranet", "https://wiki.example/w").build(), wiki,
            MediaWikiCredentialsProvider.anonymous());

    @Test
    public void discoveredResourcesCarryStableIdsRevisionLocationParentAndMetadata() throws Exception {
        List<KnowledgeResource> resources =
                source.discover(SourceScope.builder().startPoint("hauptseite").maxDepth(1).build());

        assertEquals(Arrays.asList("wiki:intranet/Hauptseite", "wiki:intranet/Handbuch", "wiki:intranet/Glossar",
                "wiki:intranet/Neuer_Name"), ids(resources));
        KnowledgeResource handbuch = resources.get(1);
        assertEquals("Handbuch", handbuch.title());
        assertEquals("wiki-intranet", handbuch.sourceId().value());
        assertEquals("text/html", handbuch.contentType());
        assertEquals("102", handbuch.revision().version());
        assertEquals(Instant.parse("2026-03-04T05:06:07Z"), handbuch.revision().modifiedAt().get());
        assertEquals(KnowledgeResourceId.of("wiki:intranet/Hauptseite"), handbuch.parentId().get());
        assertEquals("intranet", handbuch.scope());
        assertEquals(URI.create("https://wiki.example/wiki/Handbuch"), handbuch.location().get());
        assertEquals("2", handbuch.metadata().get("wiki.pageId").get());
        assertFalse(resources.get(0).parentId().isPresent());
    }

    @Test
    public void loadTurnsHtmlIntoStructuredText() throws Exception {
        KnowledgeDocument document = source.load(KnowledgeResourceId.of("wiki:intranet/Handbuch"));

        assertEquals("# Handbuch\n\n## Installation\n\nDie Installation erfolgt per Gradle.", document.text());
        assertEquals("102", document.resource().revision().version());
    }

    @Test
    public void loadOfARedirectReturnsTheTargetPage() throws Exception {
        KnowledgeDocument document = source.load(KnowledgeResourceId.of("wiki:intranet/Alter_Name"));
        assertEquals(KnowledgeResourceId.of("wiki:intranet/Neuer_Name"), document.id());
    }

    @Test
    public void loadSeesNewRevisions() throws Exception {
        KnowledgeResourceId id = KnowledgeResourceId.of("wiki:intranet/Glossar");
        String before = source.load(id).resource().revision().version();
        wiki.pages.get("Glossar").revId = 200;
        assertEquals("103", before);
        assertEquals("200", source.load(id).resource().revision().version());
    }

    @Test
    public void linksResolveRedirectsAndDropRedLinks() throws Exception {
        List<SourceLink> links = source.discoverLinks(KnowledgeResourceId.of("wiki:intranet/Hauptseite"));
        List<String> targets = new ArrayList<String>();
        for (SourceLink link : links) {
            targets.add(link.target().value());
        }
        assertEquals(Arrays.asList("wiki:intranet/Handbuch", "wiki:intranet/Glossar", "wiki:intranet/Neuer_Name"),
                targets);
        assertEquals("Neuer Name", links.get(2).label());
    }

    @Test
    public void searchMapsHitsToResourceIds() throws Exception {
        List<SourceSearchHit> hits = source.search(SourceQuery.of("Gradle"));
        assertEquals(1, hits.size());
        assertEquals(KnowledgeResourceId.of("wiki:intranet/Handbuch"), hits.get(0).resourceId());
        assertEquals("… Gradle …", hits.get(0).snippet());
    }

    @Test
    public void idsOfOtherSitesOrSchemesAreUnsupported() {
        for (String id : new String[] {"wiki:anderes/Hauptseite", "confluence:intranet/Hauptseite"}) {
            try {
                source.load(KnowledgeResourceId.of(id));
                fail(id);
            } catch (KnowledgeSourceException e) {
                assertEquals(Kind.UNSUPPORTED, e.kind());
            }
        }
    }

    @Test
    public void loginProtectedWikiUsesCredentialsAndNeverExposesThem() throws Exception {
        wiki.requiredUser = "indexer";
        wiki.requiredPassword = "Sup3r-Geheim";
        MediaWikiKnowledgeSource secured = new MediaWikiKnowledgeSource(SourceAddress.of("wiki-secure"),
                MediaWikiSiteConfig.builder("secure", "https://wiki.example/w").requiresLogin(true).build(), wiki,
                new MediaWikiCredentialsProvider() {
                    @Override
                    public MediaWikiCredentials credentialsFor(MediaWikiSiteConfig site) {
                        return new MediaWikiCredentials("indexer", "Sup3r-Geheim".toCharArray());
                    }
                });

        List<KnowledgeResource> resources = secured.discover(SourceScope.of("Hauptseite"));
        KnowledgeDocument document = secured.load(resources.get(0).id());

        assertTrue(document.text().contains("Willkommen"));
        assertFalse(document.toString().contains("Geheim"));
        assertFalse(document.resource().toString().contains("Geheim"));
        assertFalse(document.resource().metadata().toString().contains("Geheim"));
        assertFalse(secured.toString().contains("Geheim"));
    }

    private static List<String> ids(List<KnowledgeResource> resources) {
        List<String> ids = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            ids.add(resource.id().value());
        }
        return ids;
    }
}
