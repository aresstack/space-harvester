package com.aresstack.enterpriseai.source.api.testing;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Vertragstests für jeden {@link KnowledgeSourcePort}-Adapter. Ein Adapter-Modul leitet davon ab, baut seinen
 * Adapter gegen einen Fake seines Protokolls (ohne Netz) und liefert die Testdaten über die Hooks:
 *
 * <pre>
 * public class MyKnowledgeSourceContractTest extends KnowledgeSourceContractTest {
 *     protected KnowledgeSourcePort createSource() { ... }
 *     protected String linkedStartPoint() { return "Hauptseite"; }
 *     ...
 * }
 * </pre>
 */
public abstract class KnowledgeSourceContractTest {

    private KnowledgeSourcePort source;

    /** Frischer Adapter über einem Testdatenbestand, der zu den übrigen Hooks passt. */
    protected abstract KnowledgeSourcePort createSource() throws Exception;

    /** Startpunkt einer existierenden Ressource, die auf mindestens eine andere existierende Ressource verweist. */
    protected abstract String linkedStartPoint();

    /** Startpunkt, den es in der Quelle nicht gibt. */
    protected abstract String missingStartPoint();

    /** ID dieser Quelle (passendes Schema), zu der keine Ressource existiert. */
    protected abstract KnowledgeResourceId unknownResourceId();

    /** Suchtext mit mindestens einem Treffer; nur für {@link SearchableKnowledgeSource} relevant. */
    protected String searchTextWithHits() {
        return null;
    }

    @Before
    public final void createSourceUnderTest() throws Exception {
        source = createSource();
    }

    protected final KnowledgeSourcePort source() {
        return source;
    }

    @Test
    public void depthZeroDiscoversOnlyTheStartResource() throws Exception {
        List<KnowledgeResource> resources = source.discover(SourceScope.of(linkedStartPoint()));
        assertEquals(1, resources.size());
        assertEquals(source.sourceId(), resources.get(0).sourceId());
    }

    @Test
    public void resourceIdsAreStableAcrossRuns() throws Exception {
        SourceScope scope = SourceScope.builder().startPoint(linkedStartPoint()).maxDepth(1).build();
        assertEquals(ids(source.discover(scope)), ids(createSource().discover(scope)));
    }

    @Test
    public void discoveryFollowsLinksWithoutDuplicatesAndBelongsToTheSource() throws Exception {
        List<KnowledgeResource> resources =
                source.discover(SourceScope.builder().startPoint(linkedStartPoint()).maxDepth(2).build());
        assertTrue("expected the start resource and at least one linked resource", resources.size() >= 2);
        assertEquals(resources.size(), new HashSet<KnowledgeResourceId>(ids(resources)).size());
        for (KnowledgeResource resource : resources) {
            assertEquals(source.sourceId(), resource.sourceId());
        }
    }

    @Test
    public void discoveryRespectsMaxResources() throws Exception {
        List<KnowledgeResource> resources = source.discover(
                SourceScope.builder().startPoint(linkedStartPoint()).maxDepth(3).maxResources(1).build());
        assertEquals(1, resources.size());
    }

    @Test
    public void missingStartPointsAreSkipped() throws Exception {
        assertTrue(source.discover(SourceScope.of(missingStartPoint())).isEmpty());
        List<KnowledgeResource> mixed = source.discover(SourceScope.of(missingStartPoint(), linkedStartPoint()));
        assertEquals(1, mixed.size());
    }

    @Test
    public void loadReturnsTheCurrentTextOfADiscoveredResource() throws Exception {
        KnowledgeResource discovered = source.discover(SourceScope.of(linkedStartPoint())).get(0);
        KnowledgeDocument document = source.load(discovered.id());
        assertEquals(source.sourceId(), document.resource().sourceId());
        // Bei einer Weiterleitung trägt das Dokument die Ziel-ID; diese ist kanonisch und lädt sich selbst.
        KnowledgeDocument again = source.load(document.id());
        assertEquals(document.id(), again.id());
        assertEquals(document.contentHash(), again.contentHash());
    }

    @Test
    public void loadOfAnUnknownResourceIsNotFound() {
        assertKind(Kind.NOT_FOUND, new Call() {
            @Override
            public void run() throws KnowledgeSourceException {
                source.load(unknownResourceId());
            }
        });
    }

    @Test
    public void loadOfAForeignResourceIsUnsupported() {
        assertKind(Kind.UNSUPPORTED, new Call() {
            @Override
            public void run() throws KnowledgeSourceException {
                source.load(KnowledgeResourceId.of("contract-foreign", "elsewhere/x"));
            }
        });
    }

    @Test
    public void linksPointToResourcesOfTheSameSource() throws Exception {
        KnowledgeResource start = source.discover(SourceScope.of(linkedStartPoint())).get(0);
        List<SourceLink> links = source.discoverLinks(start.id());
        assertFalse(links.isEmpty());
        Set<KnowledgeResourceId> targets = new HashSet<KnowledgeResourceId>();
        for (SourceLink link : links) {
            assertTrue("duplicate link target " + link.target(), targets.add(link.target()));
            assertEquals(start.id().scheme(), link.target().scheme());
        }
        boolean loadable = false;
        for (SourceLink link : links) {
            try {
                // Ein Linkziel darf eine Weiterleitung sein; load liefert dann die ID des Ziels (siehe Port).
                assertEquals(source.sourceId(), source.load(link.target()).resource().sourceId());
                loadable = true;
            } catch (KnowledgeSourceException e) {
                assertEquals(Kind.NOT_FOUND, e.kind());
            }
        }
        assertTrue("at least one link target must be loadable", loadable);
    }

    @Test
    public void linksOfAnUnknownResourceAreNotFound() {
        assertKind(Kind.NOT_FOUND, new Call() {
            @Override
            public void run() throws KnowledgeSourceException {
                source.discoverLinks(unknownResourceId());
            }
        });
    }

    @Test
    public void searchReturnsLoadableHitsWithinTheLimit() throws Exception {
        Assume.assumeTrue(source instanceof SearchableKnowledgeSource && searchTextWithHits() != null);
        SearchableKnowledgeSource searchable = (SearchableKnowledgeSource) source;
        List<SourceSearchHit> hits = searchable.search(new SourceQuery(searchTextWithHits(), 1));
        assertEquals(1, hits.size());
        assertEquals(source.sourceId(), source.load(hits.get(0).resourceId()).resource().sourceId());
    }

    private static List<KnowledgeResourceId> ids(List<KnowledgeResource> resources) {
        List<KnowledgeResourceId> ids = new java.util.ArrayList<KnowledgeResourceId>();
        for (KnowledgeResource resource : resources) {
            ids.add(resource.id());
        }
        return ids;
    }

    private static void assertKind(Kind kind, Call call) {
        try {
            call.run();
            fail("expected KnowledgeSourceException " + kind);
        } catch (KnowledgeSourceException e) {
            assertEquals(kind, e.kind());
        }
    }

    private interface Call {
        void run() throws KnowledgeSourceException;
    }
}
