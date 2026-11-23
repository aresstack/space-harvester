package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Der Weg zum NDV-Server: Vorgabe direkt, auf Wunsch über einen HTTP-Proxy (CONNECT-Tunnel). */
public class NdvProxyTest {

    @Test
    public void withAnHttpProxyTheConnectionIsTunnelledAndTheNameStaysUnresolved() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            CompletableFuture<String> request = CompletableFuture.supplyAsync(() -> {
                try (Socket client = proxyServer.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String first = in.readLine();
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        // Kopfzeilen überspringen
                    }
                    OutputStream out = client.getOutputStream();
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    Thread.sleep(200);
                    return first;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Ndv ndv = new Ndv(0, null);
            ndv.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxyServer.getLocalPort())));
            try {
                ndv.connect("ndv.example.invalid", "8011");
                assertEquals("CONNECT ndv.example.invalid:8011 HTTP/1.1", request.get(5, TimeUnit.SECONDS));
            } finally {
                ndv.closeSocket();
            }
        }
    }

    @Test
    public void withoutAProxyItConnectsDirectly() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Boolean> accepted = CompletableFuture.supplyAsync(() -> {
                try (Socket client = server.accept()) {
                    return client.isConnected();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Ndv ndv = new Ndv(0, null);
            ndv.setProxy(null);
            try {
                ndv.connect("127.0.0.1", String.valueOf(server.getLocalPort()));
                assertTrue(accepted.get(5, TimeUnit.SECONDS));
            } finally {
                ndv.closeSocket();
            }
        }
    }
}
