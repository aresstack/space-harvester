package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException.Reason;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Fehlerabbildung und Pairing-Ablauf des Providers gegen einen Fake-KeePass-Client. */
public class KeePassRpcSecretProviderTest {

    private static final String KEY = repeat('a', 64);
    private static final String NEW_KEY = repeat('b', 64);

    private final FakeTransport transport = new FakeTransport();
    private final InMemoryPairingKeyStore store = new InMemoryPairingKeyStore();
    private final List<String> pairingPrompts = new ArrayList<String>();
    private char[] pairingAnswer = "einmal".toCharArray();

    private final KeePassRpcSecretProvider provider = new KeePassRpcSecretProvider(transport, store, name -> {
        pairingPrompts.add(name);
        return pairingAnswer == null ? null : Arrays.copyOf(pairingAnswer, pairingAnswer.length);
    });

    @Test
    public void readsUserNameAndPasswordOfTheReferencedEntry() throws Exception {
        store.save(KEY.toCharArray());
        transport.entries.put("Confluence", new String[] {"alice", "s3cr3t"});

        try (SecretMaterial material = provider.resolve(SecretRef.of("keepass:Confluence"))) {
            assertEquals("alice", material.principal());
            assertArrayEquals("s3cr3t".toCharArray(), material.copySecret());
            assertEquals(SecretRef.of("keepass:Confluence"), material.ref());
            assertFalse(material.toString().contains("s3cr3t"));
        }
        assertEquals(Arrays.asList("Confluence"), transport.lookups);
        assertTrue("Pairing nur ohne gespeicherten Schlüssel", pairingPrompts.isEmpty());
        assertTrue("Sitzung muss geschlossen werden", transport.closedSessions == 1);
    }

    @Test
    public void entryTitleWithoutSchemeAndCaseInsensitiveScheme() {
        assertEquals("Wiki Prod", KeePassRpcSecretProvider.entryTitleOf(SecretRef.of("Wiki Prod")));
        assertEquals("Wiki Prod", KeePassRpcSecretProvider.entryTitleOf(SecretRef.of("KeePass: Wiki Prod")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refWithoutTitleIsRejected() {
        KeePassRpcSecretProvider.entryTitleOf(SecretRef.of("keepass:"));
    }

    @Test
    public void missingEntryIsNotFound() {
        store.save(KEY.toCharArray());
        assertReason(Reason.NOT_FOUND, "keepass:Fehlt");
        assertEquals(1, transport.closedSessions);
    }

    @Test
    public void unreachableKeePassIsNotAvailable() {
        store.save(KEY.toCharArray());
        transport.openFailure = new KeePassRpcException(KeePassRpcException.Kind.NOT_AVAILABLE, "Timeout");
        assertReason(Reason.NOT_AVAILABLE, "keepass:Confluence");
        assertTrue(pairingPrompts.isEmpty());
    }

    @Test
    public void protocolErrorIsNotAvailable() {
        store.save(KEY.toCharArray());
        transport.lookupFailure = new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, "HMAC");
        assertReason(Reason.NOT_AVAILABLE, "keepass:Confluence");
    }

    @Test
    public void firstUsePairsAndStoresTheKey() throws Exception {
        transport.pairingResult = NEW_KEY;
        transport.entries.put("Confluence", new String[] {"alice", "s3cr3t"});

        provider.resolve(SecretRef.of("Confluence")).close();

        assertEquals(1, pairingPrompts.size());
        assertArrayEquals(NEW_KEY.toCharArray(), store.load());
        assertEquals(NEW_KEY, transport.openedWith.get(0));
    }

    @Test
    public void cancelledPairingIsCancelled() {
        pairingAnswer = null;
        assertReason(Reason.CANCELLED, "keepass:Confluence");
        assertNull(store.load());
    }

    @Test
    public void rejectedPairingIsAccessDenied() {
        transport.pairingFailure = new KeePassRpcException(KeePassRpcException.Kind.AUTH_FAILED, "M falsch");
        assertReason(Reason.ACCESS_DENIED, "keepass:Confluence");
        assertNull(store.load());
    }

    @Test
    public void revokedKeyIsReplacedByExactlyOneNewPairing() throws Exception {
        store.save(KEY.toCharArray());
        transport.rejectedKeys.add(KEY);
        transport.pairingResult = NEW_KEY;
        transport.entries.put("Confluence", new String[] {"alice", "s3cr3t"});

        provider.resolve(SecretRef.of("Confluence")).close();

        assertEquals(1, pairingPrompts.size());
        assertEquals(Arrays.asList(KEY, NEW_KEY), transport.openedWith);
        assertArrayEquals(NEW_KEY.toCharArray(), store.load());
    }

    @Test
    public void keyRejectedAgainAfterNewPairingIsAccessDenied() {
        store.save(KEY.toCharArray());
        transport.rejectedKeys.add(KEY);
        transport.rejectedKeys.add(NEW_KEY);
        transport.pairingResult = NEW_KEY;
        assertReason(Reason.ACCESS_DENIED, "keepass:Confluence");
        assertEquals(1, pairingPrompts.size());
        assertNull("nachweislich ungültiger Schlüssel darf nicht gespeichert bleiben", store.load());
    }

    @Test
    public void newKeyIsWipedWhenStoringItFails() {
        IllegalStateException failure = new IllegalStateException("Platte voll");
        KeePassRpcSecretProvider failingStore = new KeePassRpcSecretProvider(transport, new KeePassPairingKeyStore() {
            @Override
            public char[] load() {
                return null;
            }

            @Override
            public void save(char[] key) {
                throw failure;
            }

            @Override
            public void clear() {
            }
        }, name -> "einmal".toCharArray());
        try {
            failingStore.resolve(SecretRef.of("Confluence"));
            fail();
        } catch (IllegalStateException e) {
            assertEquals(failure, e);
        } catch (SecretUnavailableException e) {
            fail(e.getMessage());
        }
        assertArrayEquals(new char[64], transport.lastPairedKey);
    }

    @Test
    public void unavailableKeePassDuringPairingIsNotAvailable() {
        transport.pairingFailure = new KeePassRpcException(KeePassRpcException.Kind.NOT_AVAILABLE, "aus");
        assertReason(Reason.NOT_AVAILABLE, "keepass:Confluence");
    }

    @Test
    public void exceptionMessagesContainNoSecrets() {
        store.save(KEY.toCharArray());
        transport.entries.put("Andere", new String[] {"alice", "s3cr3t"});
        try {
            provider.resolve(SecretRef.of("keepass:Confluence"));
            fail();
        } catch (SecretUnavailableException e) {
            assertFalse(e.getMessage().contains(KEY));
            assertFalse(e.getMessage().contains("s3cr3t"));
        }
    }

    private void assertReason(Reason expected, String ref) {
        try {
            provider.resolve(SecretRef.of(ref)).close();
            fail("erwartet: " + expected);
        } catch (SecretUnavailableException e) {
            assertEquals(e.getMessage(), expected, e.reason());
            assertEquals(SecretRef.of(ref), e.ref());
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    /** Fake-KeePass-Client: Einträge, abgelehnte Schlüssel und Fehler frei einstellbar. */
    private static final class FakeTransport implements KeePassRpcTransport {

        final Map<String, String[]> entries = new HashMap<String, String[]>();
        final List<String> rejectedKeys = new ArrayList<String>();
        final List<String> openedWith = new ArrayList<String>();
        final List<String> lookups = new ArrayList<String>();
        String pairingResult = NEW_KEY;
        KeePassRpcException pairingFailure;
        KeePassRpcException openFailure;
        KeePassRpcException lookupFailure;
        int closedSessions;
        char[] lastPairedKey;

        @Override
        public char[] pair(KeePassPairingCallback callback) throws KeePassRpcException {
            if (pairingFailure != null) {
                throw pairingFailure;
            }
            char[] answer = callback.requestPairingPassword("Test");
            lastPairedKey = answer == null ? null : pairingResult.toCharArray();
            return lastPairedKey;
        }

        @Override
        public Session open(char[] sessionKey) throws KeePassRpcException {
            String key = new String(sessionKey);
            openedWith.add(key);
            if (openFailure != null) {
                throw openFailure;
            }
            if (rejectedKeys.contains(key)) {
                throw new KeePassRpcException(KeePassRpcException.Kind.AUTH_FAILED, "KCR abgelehnt");
            }
            return new Session() {
                @Override
                public KeePassEntry findEntryByTitle(String title) throws KeePassRpcException {
                    lookups.add(title);
                    if (lookupFailure != null) {
                        throw lookupFailure;
                    }
                    String[] entry = entries.get(title);
                    return entry == null ? null : new KeePassEntry(title, entry[0], entry[1].toCharArray());
                }

                @Override
                public void close() {
                    closedSessions++;
                }
            };
        }
    }
}
