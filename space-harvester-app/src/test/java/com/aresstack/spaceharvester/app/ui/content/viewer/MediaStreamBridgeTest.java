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
