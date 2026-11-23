package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Tunnel durch einen HTTP-Proxy (CONNECT): Anmeldung, Ablehnung, Datenstrom unverändert. */
class HttpConnectTunnelTest {

    @Test
    void theTunnelCarriesBytesUnchangedAndSendsBasicLogin() throws Exception {
        try (ServerSocket host = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             FakeConnectProxy proxy = new FakeConnectProxy(200)) {
            Thread.ofVirtual().start(() -> {
                try (Socket s = host.accept()) {
                    s.getOutputStream().write("HALLO".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // Test vorbei
                }
            });
            char[] password = "testpw1".toCharArray();
            try (Socket socket = HttpConnectTunnel.open("127.0.0.1", proxy.port(), "proxyuser", password,
                    "127.0.0.1", host.getLocalPort(), 5000)) {
                assertThat(new String(socket.getInputStream().readNBytes(5), StandardCharsets.US_ASCII))
                        .isEqualTo("HALLO");
            }
            assertThat(password).as("das Kennwort des Aufrufers bleibt").containsExactly("testpw1".toCharArray());
            String request = proxy.requests().get(0);
            assertThat(request).startsWith("CONNECT 127.0.0.1:" + host.getLocalPort() + " HTTP/1.1\r\n");
            assertThat(request).contains("Proxy-Authorization: Basic "
                    + Base64.getEncoder().encodeToString("proxyuser:testpw1".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void aRefusalNamesTheReasonWithoutTheSecret() throws Exception {
        try (FakeConnectProxy proxy = new FakeConnectProxy(407)) {
            assertThatThrownBy(() -> HttpConnectTunnel.open("127.0.0.1", proxy.port(), null, null, "10.0.0.7",
                    992, 5000)).isInstanceOf(IOException.class).hasMessageContaining("Anmeldung")
                    .hasMessageContaining("407");
            assertThat(proxy.requests().get(0)).doesNotContain("Proxy-Authorization");
        }
    }

    @Test
    void ipv6AddressesAreBracketedExactlyOnce() {
        assertThat(HttpConnectTunnel.authority("2001:db8::1")).isEqualTo("[2001:db8::1]");
        assertThat(HttpConnectTunnel.authority("[2001:db8::1]")).isEqualTo("[2001:db8::1]");
        assertThat(HttpConnectTunnel.authority("testhost.example")).isEqualTo("testhost.example");
    }

    @Test
    void theStatusIsReadUpToTheBlankLineOnly() throws Exception {
        InputStream in = new ByteArrayInputStream("HTTP/1.0 200 OK\r\nX: y\r\n\r\nDATEN"
                .getBytes(StandardCharsets.US_ASCII));
        assertThat(HttpConnectTunnel.readStatus(in)).isEqualTo(200);
        assertThat(new String(in.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo("DATEN");
        assertThatThrownBy(() -> HttpConnectTunnel.readStatus(new ByteArrayInputStream(
                "SSH-2.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))).isInstanceOf(IOException.class);
    }
}
