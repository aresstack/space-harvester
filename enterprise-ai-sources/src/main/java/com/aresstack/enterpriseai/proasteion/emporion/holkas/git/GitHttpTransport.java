package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProtocolException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * How the Git source reaches an HTTP(S) remote (#137): the proxy route of the application per target URL
 * ({@link HttpRoutePort}), the application's TLS trust ({@link SSLSocketFactory}) and, if configured, a read token
 * ({@link GitCredentials}) as HTTP Basic credentials. Certificate checks cannot be switched off: JGit's request to do so
 * ({@code http.sslVerify=false}) is refused. Local remotes ({@code file://}) are not touched.
 */
final class GitHttpTransport implements TransportConfigCallback {

    private final HttpRoutePort routes;
    private final SSLSocketFactory tls;
    private final GitCredentials credentials;

    /**
     * @param routes      proxy route per target; {@code null} = the JVM default (direct or system proxy)
     * @param tls         trust of the application; {@code null} = the JVM default
     * @param credentials read access; {@code null} = anonymous. Not copied: the caller clears it after the fetch.
     */
    GitHttpTransport(HttpRoutePort routes, SSLSocketFactory tls, GitCredentials credentials) {
        this.routes = routes;
        this.tls = tls;
        this.credentials = credentials;
    }

    @Override
    public void configure(Transport transport) {
        if (!(transport instanceof TransportHttp)) {
            return;
        }
        ((TransportHttp) transport).setHttpConnectionFactory(new Factory());
        if (credentials != null) {
            transport.setCredentialsProvider(new UsernamePasswordCredentialsProvider(credentials.username(),
                    credentials.token()));
        }
    }

    /** For tests: the connection factory JGit gets for HTTP(S) remotes. */
    HttpConnectionFactory factory() {
        return new Factory();
    }

    private final class Factory implements HttpConnectionFactory {

        @Override
        public HttpConnection create(URL url) throws IOException {
            return create(url, null);
        }

        @Override
        public HttpConnection create(URL url, Proxy ignored) throws IOException {
            Proxy proxy = Proxy.NO_PROXY;
            if (routes != null) {
                HttpRoute route;
                try {
                    route = routes.routeFor(url.toURI());
                } catch (URISyntaxException e) {
                    throw new IOException("keine gültige URL");
                }
                if (route.isUnavailable()) {
                    throw new IOException("Netzroute nicht verfügbar: " + route.reason());
                }
                if (route.isProxy()) {
                    proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(route.proxyHost(), route.proxyPort()));
                }
            }
            HttpURLConnection connection = (HttpURLConnection) (routes == null ? url.openConnection()
                    : url.openConnection(proxy));
            if (tls != null && connection instanceof HttpsURLConnection) {
                ((HttpsURLConnection) connection).setSSLSocketFactory(tls);
            }
            return new Connection(connection);
        }
    }

    /** JGit's view of one {@link HttpURLConnection}; TLS settings stay as the application configured them. */
    static final class Connection implements HttpConnection {

        private final HttpURLConnection connection;

        Connection(HttpURLConnection connection) {
            this.connection = connection;
        }

        /** For tests: the wrapped connection. */
        HttpURLConnection delegate() {
            return connection;
        }

        @Override
        public int getResponseCode() throws IOException {
            return connection.getResponseCode();
        }

        @Override
        public URL getURL() {
            return connection.getURL();
        }

        @Override
        public String getResponseMessage() throws IOException {
            return connection.getResponseMessage();
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return connection.getHeaderFields();
        }

        @Override
        public void setRequestProperty(String key, String value) {
            connection.setRequestProperty(key, value);
        }

        @Override
        public void setRequestMethod(String method) throws ProtocolException {
            connection.setRequestMethod(method);
        }

        @Override
        public void setUseCaches(boolean usecaches) {
            connection.setUseCaches(usecaches);
        }

        @Override
        public void setConnectTimeout(int timeout) {
            connection.setConnectTimeout(timeout);
        }

        @Override
        public void setReadTimeout(int timeout) {
            connection.setReadTimeout(timeout);
        }

        @Override
        public String getContentType() {
            return connection.getContentType();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return connection.getInputStream();
        }

        @Override
        public String getHeaderField(String name) {
            return connection.getHeaderField(name);
        }

        @Override
        public List<String> getHeaderFields(String name) {
            List<String> values = new ArrayList<String>();
            for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
                if (field.getKey() != null && field.getKey().equalsIgnoreCase(name) && field.getValue() != null) {
                    values.addAll(field.getValue());
                }
            }
            return Collections.unmodifiableList(values);
        }

        @Override
        public int getContentLength() {
            return connection.getContentLength();
        }

        @Override
        public void setInstanceFollowRedirects(boolean followRedirects) {
            connection.setInstanceFollowRedirects(followRedirects);
        }

        @Override
        public void setDoOutput(boolean dooutput) {
            connection.setDoOutput(dooutput);
        }

        @Override
        public void setFixedLengthStreamingMode(int contentLength) {
            connection.setFixedLengthStreamingMode(contentLength);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return connection.getOutputStream();
        }

        @Override
        public void setChunkedStreamingMode(int chunklen) {
            connection.setChunkedStreamingMode(chunklen);
        }

        @Override
        public String getRequestMethod() {
            return connection.getRequestMethod();
        }

        @Override
        public boolean usingProxy() {
            return connection.usingProxy();
        }

        @Override
        public void connect() throws IOException {
            connection.connect();
        }

        @Override
        public void setHostnameVerifier(HostnameVerifier hostnameverifier) throws KeyManagementException {
            throw new KeyManagementException("Die Prüfung des Hostnamens lässt sich für Git-Quellen nicht abschalten");
        }

        @Override
        public void configure(KeyManager[] km, TrustManager[] tm, SecureRandom random) throws KeyManagementException {
            throw new KeyManagementException("Die Zertifikatsprüfung lässt sich für Git-Quellen nicht abschalten");
        }
    }
}
