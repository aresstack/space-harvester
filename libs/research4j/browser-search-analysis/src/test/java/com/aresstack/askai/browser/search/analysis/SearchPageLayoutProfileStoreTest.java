package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.layout.EngineFamily;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfile;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileMatch;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileQuery;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A4e: profile stores hold STRUCTURE only (never a snapshot-local container id), match on the
 * structural key, and — for the file store — persist atomically and round-trip.
 */
public class SearchPageLayoutProfileStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private SearchPageLayoutProfile profile(String fingerprint, String regionSignature) {
        return new SearchPageLayoutProfile(EngineFamily.GOOGLE, fingerprint, 1, regionSignature,
                Arrays.asList("li(h2(a),p)"), "body>main", "digest-1", 1000L, 1000L, 1);
    }

    private SearchPageLayoutProfileQuery query(String fingerprint, String regionSignature) {
        return new SearchPageLayoutProfileQuery(EngineFamily.GOOGLE, fingerprint, "digest-1",
                regionSignature, "body>main");
    }

    @Test
    public void inMemoryStoreMatchesOnTheStructuralKey() {
        InMemorySearchPageLayoutProfileStore store = new InMemorySearchPageLayoutProfileStore();
        store.saveValidated(profile("fp-a", "main(li,li,li)"));

        assertTrue(store.find(query("fp-a", "main(li,li,li)")).matched);
        assertFalse("a different fingerprint must not match",
                store.find(query("fp-b", "main(li,li,li)")).matched);
        assertFalse("a different region signature must not match",
                store.find(query("fp-a", "section(div)")).matched);
    }

    @Test
    public void fileStorePersistsAtomicallyAndRoundTrips() throws IOException {
        Path path = folder.getRoot().toPath().resolve("profiles.jsonl");
        FileSearchPageLayoutProfileStore store = new FileSearchPageLayoutProfileStore(path);
        store.saveValidated(profile("fp-a", "main(li,li,li)"));

        assertTrue(Files.exists(path));
        assertFalse("no leftover temp file after an atomic write",
                Files.exists(path.resolveSibling("profiles.jsonl.tmp")));

        FileSearchPageLayoutProfileStore reopened = new FileSearchPageLayoutProfileStore(path);
        SearchPageLayoutProfileMatch match = reopened.find(query("fp-a", "main(li,li,li)"));
        assertTrue(match.matched);
        assertEquals(EngineFamily.GOOGLE, match.profile.engineFamily);
        assertEquals("main(li,li,li)", match.profile.resultRegionStructureSignature);
    }

    @Test
    public void persistedProfileNeverContainsASnapshotLocalContainerId() throws IOException {
        Path path = folder.getRoot().toPath().resolve("profiles.jsonl");
        FileSearchPageLayoutProfileStore store = new FileSearchPageLayoutProfileStore(path);
        store.saveValidated(profile("fp-a", "main(li,li,li)"));

        String contents = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertFalse("profiles must be structure-only, never container ids",
                contents.contains("container-"));
    }
}
