package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * TLS-Fabrik, die erst beim ersten Verbindungsaufbau entsteht: So liest die Quelle den Zertifikat-Alias aus dem
 * KeePass-Eintrag ({@code MM_CertAlias}) erst beim ersten Zugriff, nicht beim Start. Ein Fehler beim Aufbau wird
 * als {@link IOException} des Verbindungsaufbaus gemeldet und beim nächsten Mal erneut versucht.
 */
final class LazySslSocketFactory extends SSLSocketFactory {

    /** Liefert die eigentliche Fabrik. */
    interface Resolver {
        SSLSocketFactory resolve() throws IOException;
    }

    private final Resolver resolver;
    private SSLSocketFactory resolved;

    LazySslSocketFactory(Resolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver must not be null");
        }
        this.resolver = resolver;
    }

    private synchronized SSLSocketFactory delegate() throws IOException {
        if (resolved == null) {
            SSLSocketFactory factory = resolver.resolve();
            resolved = factory == null ? (SSLSocketFactory) SSLSocketFactory.getDefault() : factory;
        }
        return resolved;
    }

    private synchronized SSLSocketFactory known() {
        return resolved == null ? (SSLSocketFactory) SSLSocketFactory.getDefault() : resolved;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return known().getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return known().getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return delegate().createSocket();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return delegate().createSocket(socket, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate().createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate().createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate().createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return delegate().createSocket(address, port, localAddress, localPort);
    }

    @Override
    public String toString() {
        return "LazySslSocketFactory[Client-Zertifikat aus MM_CertAlias, "
                + (resolved == null ? "beim ersten Verbindungsaufbau" : resolved) + "]";
    }
}
