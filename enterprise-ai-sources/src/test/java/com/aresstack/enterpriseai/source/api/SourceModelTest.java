package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SourceModelTest {

    @Test
    public void scopeDefaultsAndDeduplicatesStartPoints() {
        SourceScope scope = SourceScope.of("a", "b", "a");
        assertEquals(Arrays.asList("a", "b"), scope.startPoints());
        assertEquals(0, scope.maxDepth());
        assertEquals(SourceScope.DEFAULT_MAX_RESOURCES, scope.maxResources());
        assertEquals(scope, SourceScope.builder().startPoints(Arrays.asList("a", "b")).build());
    }

    @Test
    public void scopeIsImmutable() {
        SourceScope scope = SourceScope.of("a");
        try {
            scope.startPoints().add("b");
            fail();
        } catch (UnsupportedOperationException expected) {
            // erwartet
        }
    }

    @Test
    public void scopeRejectsInvalidValues() {
        expectIllegal(new Runnable() {
            @Override
            public void run() {
                SourceScope.builder().build();
            }
        });
        expectIllegal(new Runnable() {
            @Override
            public void run() {
                SourceScope.of(" ");
            }
        });
        expectIllegal(new Runnable() {
            @Override
            public void run() {
                SourceScope.builder().maxDepth(-1);
            }
        });
        expectIllegal(new Runnable() {
            @Override
            public void run() {
                SourceScope.builder().maxResources(0);
            }
        });
    }

    @Test
    public void queryValidatesTextAndLimit() {
        assertEquals(SourceQuery.DEFAULT_LIMIT, SourceQuery.of("x").limit());
        expectIllegal(new Runnable() {
            @Override
            public void run() {
                new SourceQuery("", 1);
            }
        });
        expectIllegal(new Runnable() {
            @Override
            public void run() {
                new SourceQuery("x", 0);
            }
        });
    }

    @Test
    public void queryTitleOnlyIsOffByDefaultAndKeptByTheOtherOptions() {
        assertFalse(SourceQuery.of("x").titleOnly());
        SourceQuery title = SourceQuery.of("x").withTitleOnly(true);
        assertTrue(title.titleOnly());
        assertTrue(title.withIncludedPartitions(Arrays.asList("DEV")).withExcludedPartitions(Arrays.asList("OPS"))
                .titleOnly());
        assertFalse(title.equals(SourceQuery.of("x")));
        assertEquals(SourceQuery.of("x"), title.withTitleOnly(false));
        assertEquals(SourceQuery.of("x").hashCode(), title.withTitleOnly(false).hashCode());
    }

    @Test
    public void linksAndHitsNormalizeOptionalText() {
        KnowledgeResourceId id = KnowledgeResourceId.of("wiki:w/A");
        assertEquals("", new SourceLink(id, null).label());
        assertEquals(new SourceLink(id, "A"), new SourceLink(id, " A "));
        assertEquals("", new SourceSearchHit(id, null, null).snippet());
    }

    @Test
    public void hitPartitionIsOptionalAndNormalized() {
        KnowledgeResourceId id = KnowledgeResourceId.of("wiki:w/A");
        assertNull(new SourceSearchHit(id, "A", null).partitionKey());
        assertNull(new SourceSearchHit(id, "A", null, null, null, " ").partitionKey());
        SourceSearchHit hit = new SourceSearchHit(id, "A", null, null, null, " DEV ");
        assertEquals("DEV", hit.partitionKey());
        // Der Bereich ist Zusatzinformation wie Stand und Adresse, keine Identität des Treffers.
        assertEquals(new SourceSearchHit(id, "A", null), hit);
    }

    @Test
    public void exceptionCarriesKind() {
        KnowledgeSourceException e = new KnowledgeSourceException(Kind.NOT_FOUND, "weg");
        assertEquals(Kind.NOT_FOUND, e.kind());
        assertNull(e.getCause());
        expectIllegal(new Runnable() {
            @Override
            public void run() {
                new KnowledgeSourceException(null, "x");
            }
        });
    }

    private static void expectIllegal(Runnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // erwartet
        }
    }
}
