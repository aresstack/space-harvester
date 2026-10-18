package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException.Reason;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Create-only ({@code SecretVault.create}): the adapter checks the current state within the same session and on a
 * match calls neither {@code UpdateLogin} nor {@code AddLogin}.
 */
public class KeePassRpcCreateOnlyTest {

    private final FakeTransport transport = new FakeTransport();
    private final InMemoryPairingKeyStore store = new InMemoryPairingKeyStore();
    private final KeePassRpcSecretProvider provider = new KeePassRpcSecretProvider(transport, store,
            name -> "einmal".toCharArray());

    public KeePassRpcCreateOnlyTest() {
        char[] key = new char[64];
        Arrays.fill(key, 'a');
        store.save(key);
    }

    private static VaultEntryDraft draft(String title) {
        return new VaultEntryDraft(title, "jdoe", "neu".toCharArray(), "https://wiki.invalid",
                Collections.<String, String>emptyMap());
    }

    @Test
    public void unknownTitleIsAdded() throws Exception {
        provider.create(draft("Wiki neu"));
        assertEquals(Collections.singletonList("Wiki neu"), transport.added);
        assertEquals(0, transport.updates);
    }

    @Test
    public void exactTitleIsRejectedWithoutUpdateLogin() {
        transport.titles.add("Confluence");
        assertRejected("Confluence");
    }

    @Test
    public void titleDifferingOnlyInCaseAndWhitespaceIsRejected() {
        transport.titles.add("Confluence");
        assertRejected("  confluence ");
    }

    @Test
    public void entryCreatedAfterTheCallersLastListIsFoundByTheFreshCheck() throws Exception {
        provider.create(draft("Wiki neu"));
        transport.titles.add("WIKI 2"); // someone creates it directly in KeePass
        assertRejected("wiki 2");
        assertEquals(Collections.singletonList("Wiki neu"), transport.added);
    }

    private void assertRejected(String title) {
        try {
            provider.create(draft(title));
            fail("erwartet: ALREADY_EXISTS");
        } catch (SecretUnavailableException e) {
            assertEquals(Reason.ALREADY_EXISTS, e.reason());
        }
        assertEquals("nie UpdateLogin", 0, transport.updates);
        assertTrue("kein AddLogin für " + title, !transport.added.contains(title));
    }

    /** Exact title lookup (like a server that misses variants); the full list shows all titles. */
    private static final class FakeTransport implements KeePassRpcTransport {

        final List<String> titles = new ArrayList<String>();
        final List<String> added = new ArrayList<String>();
        int updates;

        @Override
        public char[] pair(KeePassPairingCallback callback) {
            return null;
        }

        @Override
        public Session open(char[] sessionKey) {
            return new Session() {
                @Override
                public KeePassEntry findEntryByTitle(String title) {
                    return titles.contains(title) ? new KeePassEntry(title, "", new char[0]) : null;
                }

                @Override
                public List<KeePassEntry> allEntries() {
                    List<KeePassEntry> all = new ArrayList<KeePassEntry>();
                    for (String title : titles) {
                        all.add(new KeePassEntry(title, "", new char[0]));
                    }
                    return all;
                }

                @Override
                public void addEntry(KeePassEntry entry) {
                    added.add(entry.title());
                    titles.add(entry.title());
                }

                @Override
                public void updateEntry(KeePassEntry entry, KeePassEntry existing) {
                    updates++;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
