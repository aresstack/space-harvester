package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.files.FilePatterns;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** HTTP(S) access of the Git source against a local fake server: token, proxy route, errors, TLS policy. */
public class GitHttpTransportTest {

    private static final String TOKEN = "s3cr3t-pat-4711";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<String>();
    private final List<String> requests = new CopyOnWriteArrayList<String>();
    private volatile long delayMillis;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requests.add(exchange.getRequestURI().toString());
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization != null) {
                    authorizations.add(authorization);
                }
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"repos\"");
                byte[] body = "denied".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void tokenFromTheVaultIsSentAsBasicAuthAndNeverShown() throws Exception {
        GitKnowledgeSource source = open(base() + "/org/project/_git/repo", direct(), user -> credentials());
        KnowledgeSourceException e = discoverFails(source);

        assertEquals(Kind.ACCESS_DENIED, e.kind());
        assertTrue(authorizations.toString(), authorizations.contains("Basic " + java.util.Base64.getEncoder()
                .encodeToString(("reader:" + TOKEN).getBytes(StandardCharsets.UTF_8))));
        assertFalse(e.getMessage().contains(TOKEN));
        assertFalse(String.valueOf(e.getCause()).contains(TOKEN));
        assertTrue(e.getMessage(), e.getMessage().contains("Code (Read)"));
    }

    @Test
    public void withoutTokenTheRepositoryIsAnonymousAndDenied() throws Exception {
        KnowledgeSourceException e = discoverFails(open(base() + "/public.git", direct(), null));
        assertEquals(Kind.ACCESS_DENIED, e.kind());
        assertTrue(authorizations.isEmpty());
    }

    @Test
    public void lockedVaultIsAccessDeniedWithoutNetwork() throws Exception {
        GitKnowledgeSource source = open(base() + "/repo.git", direct(), ref -> new GitCredentialsProvider() {
            @Override
            public GitCredentials credentials() throws Exception {
                throw new IllegalStateException("vault locked");
            }
        });
        assertEquals(Kind.ACCESS_DENIED, discoverFails(source).kind());
        assertTrue(requests.isEmpty());
    }

    @Test
    public void requestsTakeTheProxyRouteOfTheApplication() throws Exception {
        HttpRoutePort viaFakeProxy = target -> HttpRoute.proxy("127.0.0.1", server.getAddress().getPort(), "test");
        discoverFails(open("http://repos.example.invalid/team/app.git", viaFakeProxy, null));
        assertFalse(requests.isEmpty());
        assertTrue(requests.toString(), requests.get(0).startsWith("http://repos.example.invalid/team/app.git/"));
    }

    @Test
    public void unavailableRouteStopsTheFetch() throws Exception {
        HttpRoutePort none = target -> HttpRoute.unavailable("PAC", "kein Proxy");
        assertEquals(Kind.UNAVAILABLE, discoverFails(open(base() + "/repo.git", none, null)).kind());
        assertTrue(requests.isEmpty());
    }

    @Test
    public void timeoutIsReportedAsUnavailable() throws Exception {
        delayMillis = 4000;
        KnowledgeSourceException e = discoverFails(open(base() + "/slow.git", direct(), null, "timeoutSeconds", "1"));
        assertEquals(Kind.UNAVAILABLE, e.kind());
    }

    @Test
    public void certificateChecksCannotBeSwitchedOff() throws Exception {
        SSLSocketFactory trust = (SSLSocketFactory) SSLSocketFactory.getDefault();
        GitHttpTransport transport = new GitHttpTransport(direct(), trust, null);
        GitHttpTransport.Connection connection = (GitHttpTransport.Connection) transport.factory()
                .create(new URL("https://repos.example.invalid/x.git/info/refs"));
        assertSame(trust, ((HttpsURLConnection) connection.delegate()).getSSLSocketFactory());
        try {
            connection.configure(null, null, null);
            fail("TLS trust must not be replaceable");
        } catch (java.security.KeyManagementException expected) {
            // refused
        }
        try {
            connection.setHostnameVerifier((host, session) -> true);
            fail("hostname verification must stay on");
        } catch (java.security.KeyManagementException expected) {
            // refused
        }
    }

    @Test
    public void tokensOnlyTravelOverHttps() {
        GitSourceProvider provider = GitFixtures.provider(folder.getRoot().toPath());
        Map<String, String> values = new HashMap<String, String>();
        values.put("url", "http://repos.example.invalid/a.git");
        values.put("credentialRef", "Git PAT");
        assertEquals(1, provider.validate(SourceSettings.of(values)).size());
        values.put("url", "https://dev.azure.com/org/project/_git/repo");
        assertTrue(provider.validate(SourceSettings.of(values)).isEmpty());
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpRoutePort direct() {
        return new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(URI target) {
                return HttpRoute.direct("test");
            }
        };
    }

    private static GitCredentialsProvider credentials() {
        return new GitCredentialsProvider() {
            @Override
            public GitCredentials credentials() {
                return new GitCredentials("reader", TOKEN.toCharArray());
            }
        };
    }

    /** Built directly: the fake server speaks HTTP, while the provider only accepts tokens for HTTPS remotes. */
    private GitKnowledgeSource open(String url, HttpRoutePort routes, Function<SecretRef, GitCredentialsProvider> vault,
                                    String... keyValues) {
        int timeout = keyValues.length == 2 ? Integer.parseInt(keyValues[1]) : 10;
        GitRemote remote = GitRemote.parse(url);
        return new GitKnowledgeSource(SourceAddress.of("git", remote.locator()), remote, "", remote.locator(), null,
                new GitRepositoryCache(folder.getRoot().toPath().resolve("cache")), routes, null,
                vault == null ? null : vault.apply(SecretRef.of("Git PAT")), timeout,
                new FilePatterns(Collections.<String>emptyList(), Collections.<String>emptyList()), 1024 * 1024,
                GitFixtures.detector(), GitFixtures.registry(), 0, System::currentTimeMillis);
    }

    private static KnowledgeSourceException discoverFails(GitKnowledgeSource source) {
        try {
            source.discover(SourceScope.of("."));
            fail("expected a failure");
            return null;
        } catch (KnowledgeSourceException e) {
            return e;
        }
    }
}
