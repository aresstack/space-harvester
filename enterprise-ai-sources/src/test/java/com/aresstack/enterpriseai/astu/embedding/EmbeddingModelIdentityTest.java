package com.aresstack.enterpriseai.astu.embedding;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EmbeddingModelIdentityTest {

    @Test
    public void sameModelDimensionAndAttributesAreTheSameWorld() {
        EmbeddingModelIdentity a = EmbeddingModelIdentity.of("text-embedding-3-small", 1536)
                .withAttribute("normalization", "l2").withAttribute("encoding", "float");
        EmbeddingModelIdentity b = EmbeddingModelIdentity.of(" text-embedding-3-small ", 1536)
                .withAttribute("encoding", "float").withAttribute("normalization", "l2");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.fingerprint(), b.fingerprint());
        assertTrue(a.isSameWorldAs(b));
    }

    @Test
    public void fingerprintIsStableAcrossReleases() {
        // Der Fingerprint ist ein persistierter Schlüssel (Index-Namespace). Ändert sich dieser Wert,
        // werden alle gespeicherten Vektoren unbrauchbar – dann bewusst die Formatversion anheben.
        EmbeddingModelIdentity identity = EmbeddingModelIdentity.of("model-a", 3).withAttribute("version", "1");
        assertEquals(64, identity.fingerprint().length());
        assertEquals(identity.fingerprint(),
                EmbeddingModelIdentity.of("model-a", 3).withAttribute("version", "1").fingerprint());
        assertEquals("189d65d3e5bcc694b9ff4d6654b59d0d4151afd7906a7664966577f39837280b", identity.fingerprint());
    }

    @Test
    public void differentModelDimensionOrAttributeIsADifferentWorld() {
        EmbeddingModelIdentity base = EmbeddingModelIdentity.of("m", 8).withAttribute("version", "1");

        assertDifferentWorld(base, EmbeddingModelIdentity.of("n", 8).withAttribute("version", "1"));
        assertDifferentWorld(base, EmbeddingModelIdentity.of("m", 16).withAttribute("version", "1"));
        assertDifferentWorld(base, EmbeddingModelIdentity.of("m", 8).withAttribute("version", "2"));
        assertDifferentWorld(base, EmbeddingModelIdentity.of("m", 8));
        assertDifferentWorld(base, base.withAttribute("normalization", "l2"));
    }

    @Test
    public void attributesCannotForgeEachOther() {
        EmbeddingModelIdentity one = EmbeddingModelIdentity.of("m", 2).withAttribute("a", "1\nattr.b=2");
        EmbeddingModelIdentity two = EmbeddingModelIdentity.of("m", 2).withAttribute("a", "1").withAttribute("b", "2");
        assertDifferentWorld(one, two);

        EmbeddingModelIdentity three = EmbeddingModelIdentity.of("m", 2).withAttribute("a=", "b");
        EmbeddingModelIdentity four = EmbeddingModelIdentity.of("m", 2).withAttribute("a", "=b");
        assertDifferentWorld(three, four);
    }

    @Test
    public void withAttributeDoesNotMutateTheOriginal() {
        EmbeddingModelIdentity base = EmbeddingModelIdentity.of("m", 2);
        EmbeddingModelIdentity extended = base.withAttribute("k", "v");
        assertTrue(base.attributes().isEmpty());
        assertEquals("v", extended.attributes().get("k"));
        try {
            extended.attributes().put("x", "y");
            fail("attributes must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void requireSameWorldThrowsOnMismatch() {
        EmbeddingModelIdentity a = EmbeddingModelIdentity.of("m", 2);
        a.requireSameWorldAs(EmbeddingModelIdentity.of("m", 2));
        try {
            a.requireSameWorldAs(EmbeddingModelIdentity.of("m", 3));
            fail("mismatch expected");
        } catch (EmbeddingWorldMismatchException expected) {
            assertTrue(expected.getMessage().contains("different embedding worlds"));
        }
        try {
            a.requireSameWorldAs(null);
            fail("mismatch expected");
        } catch (EmbeddingWorldMismatchException expected) {
            // ok
        }
    }

    @Test
    public void rejectsInvalidArguments() {
        assertRejected(null, 1);
        assertRejected("  ", 1);
        assertRejected("m", 0);
        assertRejected("m", -4);
        try {
            EmbeddingModelIdentity.of("m", 1).withAttribute(" ", "v");
            fail("blank key must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            EmbeddingModelIdentity.of("m", 1).withAttribute("k", null);
            fail("null value must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static void assertRejected(String modelId, int dimension) {
        try {
            EmbeddingModelIdentity.of(modelId, dimension);
            fail("expected rejection of " + modelId + "/" + dimension);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static void assertDifferentWorld(EmbeddingModelIdentity a, EmbeddingModelIdentity b) {
        assertNotEquals(a, b);
        assertNotEquals(a.fingerprint(), b.fingerprint());
        assertFalse(a.isSameWorldAs(b));
        assertFalse(b.isSameWorldAs(a));
    }

    @Test
    public void rejectsUnpairedSurrogatesThatWouldCollideInUtf8() {
        // "?" und "\uD800" würden beide als "?" kodiert und denselben Fingerprint ergeben.
        EmbeddingModelIdentity.of("m", 1).withAttribute("a", "?");
        for (String malformed : new String[] {"\uD800", "\uDC00", "x\uD800", "\uDC00\uD800"}) {
            try {
                EmbeddingModelIdentity.of("m", 1).withAttribute("a", malformed);
                fail("unpaired surrogate in value must be rejected");
            } catch (IllegalArgumentException expected) {
                // ok
            }
            try {
                EmbeddingModelIdentity.of("m" + malformed, 1);
                fail("unpaired surrogate in model id must be rejected");
            } catch (IllegalArgumentException expected) {
                // ok
            }
            try {
                EmbeddingModelIdentity.of("m", 1).withAttribute("k" + malformed, "v");
                fail("unpaired surrogate in key must be rejected");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void acceptsValidSurrogatePairs() {
        EmbeddingModelIdentity a = EmbeddingModelIdentity.of("m\uD83D\uDE00", 1).withAttribute("emoji", "\uD83D\uDE00");
        EmbeddingModelIdentity b = EmbeddingModelIdentity.of("m\uD83D\uDE01", 1).withAttribute("emoji", "\uD83D\uDE00");
        assertDifferentWorld(a, b);
    }
}
