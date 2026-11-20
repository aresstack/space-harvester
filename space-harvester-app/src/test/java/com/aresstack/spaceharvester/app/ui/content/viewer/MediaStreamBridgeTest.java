package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MediaStreamBridgeTest {

    @Test
    void opensOnlyRequestedBytesAfterPlaybackRequest() throws Exception {
        byte[] content = "012345678901234567890123".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger downloads = new AtomicInteger();
        try (MediaStreamBridge bridge = new MediaStreamBridge((offset, length) -> {
            downloads.incrementAndGet();
            byte[] block = new byte[length];
            System.arraycopy(content, (int) offset, block, 0, length);
            return block;
        }, content.length, "video/mp4")) {
            assertThat(bridge.address().getHost()).isEqualTo("127.0.0.1");
            assertThat(downloads).hasValue(0);

            HttpURLConnection metadata = connection(bridge.address().toURL(), "HEAD", null);
            assertThat(metadata.getResponseCode()).isEqualTo(200);
            assertThat(metadata.getHeaderFieldLong("Content-Length", -1)).isEqualTo(content.length);
            assertThat(downloads).hasValue(0);
            metadata.disconnect();

            HttpURLConnection partial = connection(bridge.address().toURL(), "GET", "bytes=2-5");
            assertThat(partial.getResponseCode()).isEqualTo(206);
            assertThat(partial.getHeaderField("Content-Range")).isEqualTo("bytes 2-5/24");
            assertThat(partial.getInputStream().readAllBytes()).isEqualTo("2345".getBytes(StandardCharsets.US_ASCII));
            assertThat(downloads).hasValue(1);
            partial.disconnect();

            HttpURLConnection invalid = connection(bridge.address().toURL(), "GET", "bytes=30-");
            assertThat(invalid.getResponseCode()).isEqualTo(416);
            assertThat(downloads).hasValue(1);
            invalid.disconnect();
        }
    }

    @Test
    void reportsBscwRangeFailuresWithoutExposingSourceCredentials() throws Exception {
        try (MediaStreamBridge bridge = new MediaStreamBridge((offset, length) -> {
            throw new java.io.IOException("secret-password-123");
        }, 1024, "video/mp4")) {
            HttpURLConnection request = connection(bridge.address().toURL(), "GET", "bytes=0-31");
            assertThat(request.getResponseCode()).isEqualTo(502);
            assertThat(bridge.lastFailure()).contains("BSCW").doesNotContain("secret-password-123");
            request.disconnect();
        }
    }

    /**
     * A non-interleaved MP4 makes VLC jump between two file regions; jumping back must be served from the
     * chunks read ahead instead of waiting for another source round trip.
     */
    @Test
    void readsAheadSoThatAJumpBackIsServedWithoutAnotherSourceRead() throws Exception {
        int chunk = MediaStreamBridge.CHUNK_BYTES;
        byte[] content = new byte[chunk * 12];
        new java.util.Random(1).nextBytes(content);
        java.util.Set<Long> requested = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try (MediaStreamBridge bridge = new MediaStreamBridge((offset, length) -> {
            requested.add(offset);
            return java.util.Arrays.copyOfRange(content, (int) offset, (int) offset + length);
        }, content.length, "video/mp4")) {
            HttpURLConnection first = connection(bridge.address().toURL(), "GET", "bytes=100-199");
            assertThat(first.getInputStream().readAllBytes())
                    .isEqualTo(java.util.Arrays.copyOfRange(content, 100, 200));
            first.disconnect();
            long deadline = System.currentTimeMillis() + 5000;
            while (requested.size() < 1 + MediaStreamBridge.READ_AHEAD_CHUNKS && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(requested).hasSize(1 + MediaStreamBridge.READ_AHEAD_CHUNKS);

            int before = requested.size();
            int from = chunk * 3 + 17;
            HttpURLConnection jump = connection(bridge.address().toURL(), "GET",
                    "bytes=" + from + "-" + (from + chunk));
            assertThat(jump.getInputStream().readAllBytes())
                    .isEqualTo(java.util.Arrays.copyOfRange(content, from, from + chunk + 1));
            jump.disconnect();
            assertThat(requested).contains(0L, (long) chunk * 3, (long) chunk * 4);
            assertThat(requested.size()).isLessThanOrEqualTo(before + 4);
        }
    }

    @Test
    void retriesAChunkWhoseSourceReadFailed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        byte[] content = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        try (MediaStreamBridge bridge = new MediaStreamBridge((offset, length) -> {
            if (calls.incrementAndGet() == 1) {
                throw new java.io.IOException("temporarily unavailable");
            }
            return java.util.Arrays.copyOfRange(content, (int) offset, (int) offset + length);
        }, content.length, "video/mp4")) {
            HttpURLConnection failed = connection(bridge.address().toURL(), "GET", "bytes=0-3");
            assertThat(failed.getResponseCode()).isEqualTo(502);
            failed.disconnect();
            HttpURLConnection retried = connection(bridge.address().toURL(), "GET", "bytes=0-3");
            assertThat(retried.getInputStream().readAllBytes()).isEqualTo("abcd".getBytes(StandardCharsets.US_ASCII));
            retried.disconnect();
        }
    }

    private static HttpURLConnection connection(URL url, String method, String range) throws Exception {
        HttpURLConnection request = (HttpURLConnection) url.openConnection();
        request.setRequestMethod(method);
        request.setConnectTimeout(2000);
        request.setReadTimeout(2000);
        if (range != null) {
            request.setRequestProperty("Range", range);
        }
        return request;
    }
}
