package com.aresstack.enterpriseai.adyton;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SecretProviderTest {

    private static final SecretRef REF = SecretRef.of("keepass:confluence");

    private final List<SecretMaterial> issued = new ArrayList<SecretMaterial>();

    private final SecretProvider provider = new SecretProvider() {
        @Override
        public SecretMaterial resolve(SecretRef ref) throws SecretUnavailableException {
            if (!REF.equals(ref)) {
                throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref, "kein Eintrag");
            }
            SecretMaterial material = new SecretMaterial(ref, "alice", "s3cr3t".toCharArray());
            issued.add(material);
            return material;
        }
    };

    @Test
    public void withSecretClosesTheMaterialAfterUse() throws Exception {
        String principal = provider.withSecret(REF, material -> material.principal());

        assertEquals("alice", principal);
        assertEquals(1, issued.size());
        assertTrue(issued.get(0).isClosed());
    }

    @Test
    public void withSecretClosesTheMaterialWhenTheUseFails() throws Exception {
        IOException failure = new IOException("transport kaputt");
        try {
            provider.withSecret(REF, material -> {
                throw failure;
            });
            fail("Fehler der Verwendung muss durchgereicht werden");
        } catch (IOException e) {
            assertSame(failure, e);
        }
        assertTrue(issued.get(0).isClosed());
    }

    @Test
    public void unavailabilityKeepsReasonAndRef() {
        SecretRef unknown = SecretRef.of("keepass:unknown");
        try {
            provider.withSecret(unknown, SecretMaterial::principal);
            fail();
        } catch (SecretUnavailableException e) {
            assertEquals(SecretUnavailableException.Reason.NOT_FOUND, e.reason());
            assertEquals(unknown, e.ref());
            assertTrue(e.getMessage(), e.getMessage().contains("keepass:unknown"));
        }
        assertTrue(issued.isEmpty());
    }

    @Test
    public void nullMaterialIsTreatedAsNotFound() {
        SecretProvider broken = ref -> null;
        try {
            broken.withSecret(REF, SecretMaterial::principal);
            fail();
        } catch (SecretUnavailableException e) {
            assertEquals(SecretUnavailableException.Reason.NOT_FOUND, e.reason());
        }
    }

    @Test
    public void exceptionMessageNeverContainsSecretMaterial() {
        SecretUnavailableException e = new SecretUnavailableException(
                SecretUnavailableException.Reason.NOT_AVAILABLE, REF, "KeePass nicht erreichbar");
        assertEquals("NOT_AVAILABLE SecretRef[keepass:confluence]: KeePass nicht erreichbar", e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"));
    }
}
