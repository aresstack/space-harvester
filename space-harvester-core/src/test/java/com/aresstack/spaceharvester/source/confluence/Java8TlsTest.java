package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.Test;

/** TLS zur Confluence wie im Enterprise AI Client unter Java 8 (#571). */
class Java8TlsTest {

    @Test
    void connectionsOfTheDesktopTransportOfferOnlyTls12LikeJava8() throws Exception {
        SSLSocketFactory jvm = SSLContext.getDefault().getSocketFactory();
        try (Socket plain = jvm.createSocket()) {
            // Ohne Beschränkung bietet Java 21 auch TLS 1.3 an.
            assertThat(((SSLSocket) plain).getEnabledProtocols()).contains("TLSv1.3");
        }

        SSLSocketFactory restricted = Java8Tls.of(jvm);
        try (Socket socket = restricted.createSocket()) {
            assertThat(((SSLSocket) socket).getEnabledProtocols()).containsExactly("TLSv1.2");
        }
        try (Socket socket = Java8Tls.of(null).createSocket()) {
            assertThat(((SSLSocket) socket).getEnabledProtocols()).containsExactly("TLSv1.2");
        }
        assertThat(Java8Tls.of(restricted)).isSameAs(restricted);
    }
}
