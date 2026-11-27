package com.aresstack.askai.research.runtime.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BrowserUserContextRequestTest {

    @Test
    public void aRequestIsEphemeralUnlessItNamesAStateKey() {
        BrowserUserContextRequest request = BrowserUserContextRequest.of(" worker-1 ", "task-7");
        assertEquals("worker-1", request.workerId());
        assertNull(request.stateKey());
        assertEquals(0L, request.acquireTimeoutMillis());
        BrowserUserContextRequest keeping = request.withStateKey("account.alice").withAcquireTimeoutMillis(500);
        assertEquals("account.alice", keeping.stateKey());
        assertEquals(500L, keeping.acquireTimeoutMillis());
        assertTrue(keeping.toString().contains("state=account.alice"));
    }

    @Test
    public void invalidInputIsRejected() {
        for (String bad : new String[]{"../x", "a/b", "with space"}) {
            try {
                BrowserUserContextRequest.of("w", "t").withStateKey(bad);
                fail("must reject " + bad);
            } catch (IllegalArgumentException expected) {
                // rejected
            }
        }
        try {
            BrowserUserContextRequest.of(" ", "t");
            fail("a worker id is required");
        } catch (IllegalArgumentException expected) {
            // rejected
        }
    }

    @Test
    public void anUnavailablePoolFailsReadably() throws Exception {
        BrowserUserContextPool none = BrowserUserContextPool.unavailable("no browser");
        assertEquals(0, none.capacity());
        try {
            none.acquire(BrowserUserContextRequest.of("w", "t"));
            fail();
        } catch (BrowserCapacityException expected) {
            assertTrue(expected.getMessage().contains("no browser"));
        }
    }
}
