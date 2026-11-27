package com.aresstack.askai.agent.model;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The bearer header without the key in any message, and connections through the host's source. */
public class EndpointAuthorizationTest {

    @After
    public void tearDown() {
        EndpointAuthorization.install(null);
    }

    private static HttpURLConnection connection() throws IOException {
        return (HttpURLConnection) new URL("http://127.0.0.1:9/v1/embeddings").openConnection();
    }

    @Test
    public void aKeyWithALineBreakIsRefusedWithoutTheKeyAndCleared() throws Exception {
        char[] key = "sk-secret\nInjected: 1".toCharArray();
        try {
            EndpointAuthorization.authorize(connection(), key);
            fail("a key with a line break must be refused");
        } catch (IOException refused) {
            assertEquals(EndpointAuthorization.KEY_WITH_CONTROL_CHARACTERS, refused.getMessage());
            assertFalse(String.valueOf(refused.getMessage()).contains("sk-secret"));
        }
        assertArrayEquals(new char[key.length], key);
    }

    @Test
    public void aGoodKeyIsSetTrimmedAndCleared() throws Exception {
        HttpURLConnection connection = connection();
        char[] key = " sk-good ".toCharArray();
        assertTrue(EndpointAuthorization.authorize(connection, key));
        assertArrayEquals(new char[key.length], key);
        assertFalse(EndpointAuthorization.authorize(connection, "   ".toCharArray()));
        assertFalse(EndpointAuthorization.authorize(connection, null));
    }

    @Test
    public void connectionsComeFromTheInstalledSource() throws Exception {
        final HttpURLConnection configured = connection();
        EndpointAuthorization.install(new EndpointAuthorization.Source() {
            public char[] bearerFor(String url) {
                return null;
            }

            public HttpURLConnection open(String url) {
                return configured;
            }
        });
        assertSame(configured, EndpointAuthorization.open("http://127.0.0.1:9/v1/rerank"));
        EndpointAuthorization.install(null);
        assertFalse(configured == EndpointAuthorization.open("http://127.0.0.1:9/v1/rerank"));
    }
}
