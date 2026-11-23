package com.aresstack.enterpriseai.browser.firefox;

import com.aresstack.enterpriseai.browser.api.BrowserException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FirefoxEngineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void profileInUseIsRefusedInsteadOfOpenedTwice() throws Exception {
        Path profile = folder.newFolder("chat-a").toPath();
        FirefoxEngine.ProfileLock first = FirefoxEngine.ProfileLock.acquire(profile);
        try {
            FirefoxEngine.ProfileLock.acquire(profile);
            fail("second lock on the same profile must fail");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.START_FAILED, expected.kind());
        } finally {
            first.release();
        }
        FirefoxEngine.ProfileLock.acquire(profile).release();
    }

    @Test
    public void defaultsAreWrittenOnceAndUserChangesStay() throws Exception {
        Path profile = folder.newFolder("chat-b").toPath();
        FirefoxEngine.writeDefaultsOnce(profile);
        Path userJs = profile.resolve("user.js");
        assertTrue(new String(Files.readAllBytes(userJs), StandardCharsets.UTF_8).contains("checkDefaultBrowser"));

        Files.write(userJs, "// mine\n".getBytes(StandardCharsets.UTF_8));
        FirefoxEngine.writeDefaultsOnce(profile);
        assertEquals("// mine\n", new String(Files.readAllBytes(userJs), StandardCharsets.UTF_8));
    }

    @Test
    public void freePortIsOnLoopback() throws Exception {
        int port = FirefoxEngine.freeLoopbackPort();
        assertTrue(port > 0 && port < 65536);
    }

    @Test
    public void missingConfiguredExecutableIsNotFound() {
        assertEquals(null, FirefoxEngine.locate(folder.getRoot().toPath().resolve("no-firefox").toString(),
                java.util.Collections.<String, String>emptyMap(), "Linux"));
    }

    @Test
    public void redirectAbortIsRecognisedAndOriginsMatchLocationOrigin() {
        assertTrue(BidiBrowserConnection.isAbortedByRedirect(new RuntimeException("Error: NS_BINDING_ABORTED")));
        assertTrue(!BidiBrowserConnection.isAbortedByRedirect(new RuntimeException("Error: NS_ERROR_UNKNOWN_HOST")));
        assertEquals("https://mapz.example", BidiBrowserConnection.originOf("https://MAPZ.example:443/a/b.html?x"));
        assertEquals("http://intra.example:8080", BidiBrowserConnection.originOf("http://intra.example:8080/"));
        assertEquals(null, BidiBrowserConnection.originOf("about:blank"));
    }

    @Test
    public void redirectCountsOnlyAfterAReplacementAndBackOnTheOrigin() {
        // Launch page quiet on the origin while the provider still answers: not yet.
        assertTrue(!BidiBrowserConnection.isRedirectSettled(false, 1, true, 5000, true));
        // Back from the provider, quiet: done.
        assertTrue(BidiBrowserConnection.isRedirectSettled(true, 3, true, 700, true));
        // Same-origin script redirect: launch page plus target.
        assertTrue(BidiBrowserConnection.isRedirectSettled(false, 2, true, 700, true));
        // Still on the provider or still changing: not yet.
        assertTrue(!BidiBrowserConnection.isRedirectSettled(true, 2, true, 700, false));
        assertTrue(!BidiBrowserConnection.isRedirectSettled(true, 3, true, 100, true));
    }
}
