package com.aresstack.askai.browser.statichttp;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.WebSearchResult;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The STATIC_HTTP backend: fetches a page once over {@link HttpURLConnection} and cleans it with jsoup
 * (navigation/script/style/cookie chrome removed, headings and paragraphs kept). Honest capability limits:
 * no JavaScript, no dynamic pages, no interaction, and {@link #search} is NOT supported — it fails with a
 * readable error instead of pretending. Links get stable per-session ids; {@code follow}/{@code back} work on
 * the visited-page stack. Every fetch passes the {@link UrlSafetyPolicy} and the {@link BrowserLimits}.
 */
public final class StaticHttpBrowserSession implements BrowserSession {

    private final UrlSafetyPolicy safety;
    private final BrowserLimits limits;
    private final Deque<Page> history = new ArrayDeque<Page>();
    private Page current;
    private int nextLinkId = 1;
    private boolean closed;

    public StaticHttpBrowserSession(UrlSafetyPolicy safety, BrowserLimits limits) {
        this.safety = safety;
        this.limits = limits;
    }

    @Override
    public BrowserBackendKind getBackendKind() {
        return BrowserBackendKind.STATIC_HTTP;
    }

    @Override
    public WebSearchResult search(String query) throws BrowserException {
        throw new BrowserException("The STATIC_HTTP backend cannot search the web. "
                + "Use web_open with a known URL, or run the Playwright sidecar backend.");
    }

    @Override
    public BrowserPageSnapshot open(String url) throws BrowserException {
        ensureOpen();
        URI uri = safety.check(url);
        Page page = fetch(uri.toString());
        if (current != null) {
            history.push(current);
        }
        current = page;
        return page.snapshot;
    }

    @Override
    public BrowserPageSnapshot currentPage() throws BrowserException {
        ensureOpen();
        if (current == null) {
            throw new BrowserException("No page is open yet. Use web_open first.");
        }
        return current.snapshot;
    }

    @Override
    public List<BrowserLink> links() throws BrowserException {
        ensureOpen();
        if (current == null) {
            throw new BrowserException("No page is open yet. Use web_open first.");
        }
        return new ArrayList<BrowserLink>(current.links.values());
    }

    @Override
    public BrowserPageSnapshot follow(String linkId) throws BrowserException {
        ensureOpen();
        if (current == null) {
            throw new BrowserException("No page is open yet. Use web_open first.");
        }
        BrowserLink link = current.links.get(linkId);
        if (link == null) {
            throw new BrowserException("Unknown link id: " + linkId);
        }
        return open(link.getUrl());
    }

    @Override
    public BrowserPageSnapshot back() throws BrowserException {
        ensureOpen();
        if (history.isEmpty()) {
            throw new BrowserException("No previous page.");
        }
        current = history.pop();
        return current.snapshot;
    }

    @Override
    public void close() {
        closed = true;
        history.clear();
        current = null;
    }

    // ------------------------------------------------------------------ internals

    private void ensureOpen() throws BrowserException {
        if (closed) {
            throw new BrowserException("The browser session is closed.");
        }
    }

    private Page fetch(String url) throws BrowserException {
        String html = download(url);
        Document doc = Jsoup.parse(html, url);
        // Remove non-content chrome; keep main text structure.
        doc.select("script, style, nav, header, footer, aside, form, noscript, iframe,"
                + " [class*=cookie], [id*=cookie], [class*=consent]").remove();

        String title = doc.title();
        String text = doc.body() == null ? "" : doc.body().text();
        boolean truncated = false;
        if (text.length() > limits.getMaxTextChars()) {
            text = text.substring(0, limits.getMaxTextChars());
            truncated = true;
        }

        Map<String, BrowserLink> links = new LinkedHashMap<String, BrowserLink>();
        for (Element a : doc.select("a[href]")) {
            if (links.size() >= limits.getMaxLinks()) {
                break;
            }
            String abs = a.absUrl("href");
            if (abs.isEmpty() || !(abs.startsWith("http://") || abs.startsWith("https://"))) {
                continue;
            }
            String id = "l" + (nextLinkId++);
            links.put(id, new BrowserLink(id, a.text(), abs));
        }
        return new Page(new BrowserPageSnapshot(url, title, text, truncated), links);
    }

    private String download(String url) throws BrowserException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(limits.getTimeoutMillis());
            connection.setReadTimeout(limits.getTimeoutMillis());
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "AskAI-Research/0.1 (static)");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new BrowserException("HTTP " + status + " for " + url);
            }
            InputStream in = connection.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > limits.getMaxDownloadBytes()) {
                        throw new BrowserException("Response exceeds the download limit for " + url);
                    }
                    out.write(buffer, 0, read);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (IOException ex) {
            throw new BrowserException("Fetch failed for " + url + ": " + ex.getMessage(), ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static final class Page {
        private final BrowserPageSnapshot snapshot;
        private final Map<String, BrowserLink> links;

        private Page(BrowserPageSnapshot snapshot, Map<String, BrowserLink> links) {
            this.snapshot = snapshot;
            this.links = links;
        }
    }
}
