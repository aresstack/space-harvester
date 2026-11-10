package com.aresstack.spaceharvester.source.confluence;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * TLS zur Confluence wie im Enterprise AI Client, der unter Java 8 läuft (#571): TLS 1.2 mit Wiederaufnahme der
 * Sitzung über ihre Kennung statt TLS 1.3 mit Tickets, wie Java 21 es sonst aushandelt. Die Einmalcode-Sitzung von
 * Confluence (Cookie {@code JSESSIONID}) gilt nur auf dem Knoten, der die Code-Seite ausgeliefert hat; ein
 * Lastverteiler, der Verbindungen an der TLS-Sitzung festhält, schickt unter TLS 1.3 jede neue Verbindung womöglich an
 * einen anderen Knoten. Dort kennt niemand die Sitzung: Confluence setzt ein neues {@code JSESSIONID} (Angelos Log,
 * beta.344) und das Absenden des Codes endet mit HTTP 500. Unter Java 8 (TLS 1.2, Sitzungskennung) funktioniert
 * derselbe Code.
 */
public final class Java8Tls {

    static final String TLS_12 = "TLSv1.2";
    /** Java 8 sendet keine Session-Tickets; die Sitzung wird über ihre Kennung wieder aufgenommen. */
    static final String SESSION_TICKETS = "jdk.tls.client.enableSessionTicketExtension";

    private Java8Tls() {
    }

    /**
     * Schaltet Session-Tickets für TLS-Clients wie unter Java 8 ab, sofern die Property nicht gesetzt ist. Das JDK liest
     * sie nur einmal; die Desktop-App ruft das deshalb beim Start, vor der ersten Verbindung.
     */
    public static void withoutSessionTickets() {
        if (System.getProperty(SESSION_TICKETS) == null) {
            System.setProperty(SESSION_TICKETS, "false");
        }
    }

    /** {@code factory} mit nur TLS 1.2; {@code null}: die Fabrik der JVM. */
    static SSLSocketFactory of(SSLSocketFactory factory) {
        if (factory == null) {
            try {
                factory = SSLContext.getDefault().getSocketFactory();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("TLS-Kontext der JVM nicht verfügbar", e);
            }
        }
        return factory instanceof Tls12 ? factory : new Tls12(factory);
    }

    /** Beschränkt jede erzeugte Verbindung auf TLS 1.2, sofern die JVM es anbietet. */
    static final class Tls12 extends SSLSocketFactory {

        private final SSLSocketFactory delegate;

        Tls12(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return restrict(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override
        public Socket createSocket() throws IOException {
            return restrict(delegate.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return restrict(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return restrict(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return restrict(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return restrict(delegate.createSocket(address, port, localAddress, localPort));
        }

        private static Socket restrict(Socket socket) {
            if (socket instanceof SSLSocket ssl && Arrays.asList(ssl.getSupportedProtocols()).contains(TLS_12)) {
                ssl.setEnabledProtocols(new String[] {TLS_12});
            }
            return socket;
        }

        @Override
        public String toString() {
            return "TLSv1.2[" + delegate + "]";
        }
    }
}
