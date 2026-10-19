package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Aufrufe der MediaWiki-Action-API (paketintern): Seite parsen, Seiteninfos, Links, Suche, Login.
 *
 * <p>Abfragen und Login-Ablauf sind aus MainframeMate {@code JwbfWikiContentService} übernommen
 * ({@code action=parse}, {@code prop=links} mit {@code plcontinue}, {@code list=search}; Login über
 * {@code action=login} mit NeedToken-Variante und {@code clientlogin} als Rückfall). Geändert: neutrale
 * Fehlerarten statt {@code IOException}, Links auf konfigurierte Namensräume begrenzt, Redirects aufgelöst,
 * keine Tokens, Cookies oder Antwortkörper im Log, Anmeldedaten nach dem Login gelöscht und bei
 * abgelaufener Sitzung genau ein erneuter Login.
 */
final class MediaWikiClient {

    private static final Logger LOG = Logger.getLogger(MediaWikiClient.class.getName());

    /** Höchstzahl an Fortsetzungsseiten für Links (wie MainframeMate). */
    static final int MAX_LINK_PAGES = 50;
    /** Höchstzahl Titel je {@code prop=info}-Anfrage (API-Limit für normale Nutzer). */
    static final int MAX_TITLES_PER_QUERY = 50;
    /** Höchstzahl Treffer je Suche (API-Limit für normale Nutzer). */
    static final int MAX_SEARCH_LIMIT = 50;

    private final MediaWikiSiteConfig site;
    private final MediaWikiTransport transport;
    private final MediaWikiCredentialsProvider credentialsProvider;
    private final Object loginLock = new Object();
    private volatile boolean loggedIn;
    /** Zählt erfolgreiche Logins; nur unter {@code loginLock} geändert. */
    private volatile long sessionGeneration;

    MediaWikiClient(MediaWikiSiteConfig site, MediaWikiTransport transport,
                    MediaWikiCredentialsProvider credentialsProvider) {
        this.site = site;
        this.transport = transport;
        this.credentialsProvider = credentialsProvider;
    }

    WikiParsedPage parse(String title) throws KnowledgeSourceException {
        String query = "action=parse&format=json&formatversion=2&prop=text%7Crevid&redirects=1"
                + "&disabletoc=1&disableeditsection=1&page=" + enc(title);
        return MediaWikiResponses.parsedPage(read(query));
    }

    /** Seiteninfos zu den Titeln, Weiterleitungen und Normalisierung bereits aufgelöst. */
    List<WikiPageInfo> pageInfos(List<String> titles) throws KnowledgeSourceException {
        List<WikiPageInfo> result = new ArrayList<WikiPageInfo>();
        for (int from = 0; from < titles.size(); from += MAX_TITLES_PER_QUERY) {
            List<String> batch = titles.subList(from, Math.min(titles.size(), from + MAX_TITLES_PER_QUERY));
            String query = "action=query&format=json&formatversion=2&prop=info&inprop=url&redirects=1&titles="
                    + enc(join(batch));
            result.addAll(MediaWikiResponses.pageInfos(read(query)));
        }
        return result;
    }

    List<String> outgoingLinks(String title) throws KnowledgeSourceException {
        List<String> all = new ArrayList<String>();
        String continuation = null;
        for (int page = 0; page < MAX_LINK_PAGES; page++) {
            StringBuilder query = new StringBuilder(
                    "action=query&format=json&formatversion=2&prop=links&pllimit=max&redirects=1");
            query.append("&plnamespace=").append(enc(site.linkNamespaces()));
            query.append("&titles=").append(enc(title));
            if (continuation != null) {
                query.append("&plcontinue=").append(enc(continuation));
            }
            MediaWikiResponses.LinksPage links = MediaWikiResponses.links(read(query.toString()));
            all.addAll(links.titles);
            continuation = links.continuation;
            if (continuation == null) {
                break;
            }
        }
        return all;
    }

    List<WikiSearchHit> search(String text, int limit) throws KnowledgeSourceException {
        String query = "action=query&format=json&formatversion=2&list=search&srprop=snippet%7Ctimestamp"
                + "&srlimit=" + Math.min(limit, MAX_SEARCH_LIMIT)
                + "&srnamespace=" + enc(site.linkNamespaces())
                + "&srsearch=" + enc(text);
        return MediaWikiResponses.searchHits(read(query));
    }

    /** Lesender Aufruf; bei abgelaufener Sitzung genau ein erneuter Login. */
    private String read(String query) throws KnowledgeSourceException {
        ensureLoggedIn();
        long generation = sessionGeneration;
        try {
            return checked(query);
        } catch (KnowledgeSourceException e) {
            if (e.kind() != Kind.ACCESS_DENIED || !site.requiresLogin()) {
                throw e;
            }
            LOG.fine("[MediaWiki] access denied for " + site.siteKey() + ", logging in again");
            invalidateSession(generation);
            ensureLoggedIn();
            return checked(query);
        }
    }

    private String checked(String query) throws KnowledgeSourceException {
        MediaWikiTransport.Response response;
        try {
            response = transport.get(query);
        } catch (IOException e) {
            throw unavailable(e);
        }
        String body = requireOk(response);
        // Fehlerobjekt der API sofort in eine neutrale Fehlerart übersetzen.
        MediaWikiResponses.parseObject(body);
        return body;
    }

    private String requireOk(MediaWikiTransport.Response response) throws KnowledgeSourceException {
        int status = response.status();
        if (status == 200) {
            return response.body();
        }
        if (status == 401 || status == 403) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED,
                    "MediaWiki " + site.siteKey() + " denied access (HTTP " + status + ")");
        }
        if (status == 404 || status == 408 || status == 429 || status >= 500) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                    "MediaWiki " + site.siteKey() + " unavailable (HTTP " + status + ")");
        }
        throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                "MediaWiki " + site.siteKey() + " answered HTTP " + status);
    }

    private KnowledgeSourceException unavailable(IOException e) {
        // Nur die Ausnahmeklasse übernehmen: Meldungen von Netzwerkfehlern können URLs mit Parametern tragen.
        return new KnowledgeSourceException(Kind.UNAVAILABLE,
                "MediaWiki " + site.siteKey() + " not reachable (" + e.getClass().getSimpleName() + ")");
    }

    // ── Login ───────────────────────────────────────────────────────────────────────────────

    /**
     * Verwirft die Sitzung nur, wenn seit dem fehlgeschlagenen Aufruf kein anderer Thread neu angemeldet hat;
     * sonst nutzt der erneute Versuch die frische Sitzung.
     */
    private void invalidateSession(long observedGeneration) {
        synchronized (loginLock) {
            if (sessionGeneration == observedGeneration) {
                loggedIn = false;
            }
        }
    }

    private void ensureLoggedIn() throws KnowledgeSourceException {
        if (!site.requiresLogin() || loggedIn) {
            return;
        }
        synchronized (loginLock) {
            if (loggedIn) {
                return;
            }
            MediaWikiCredentials credentials;
            try {
                credentials = credentialsProvider.credentialsFor(site);
            } catch (Exception e) {
                throw new KnowledgeSourceException(Kind.ACCESS_DENIED,
                        "no credentials available for MediaWiki " + site.siteKey());
            }
            if (credentials == null) {
                // Wie MainframeMate: ohne Anmeldedaten anonym weiterlesen; verweigert das Wiki den Zugriff,
                // meldet der lesende Aufruf ACCESS_DENIED.
                LOG.warning("[MediaWiki] " + site.siteKey() + " requires login but no credentials were provided");
                sessionGeneration++;
                loggedIn = true;
                return;
            }
            try {
                login(credentials);
                sessionGeneration++;
                loggedIn = true;
            } finally {
                credentials.clear();
            }
        }
    }

    private void login(MediaWikiCredentials credentials) throws KnowledgeSourceException {
        LOG.info("[MediaWiki] login to " + site.siteKey());
        transport.resetSession();
        String password = new String(credentials.password());

        String token = loginToken();
        JsonObject result = loginPost("action=login&format=json&lgname=" + enc(credentials.username())
                + "&lgpassword=" + enc(password) + "&lgtoken=" + enc(token));
        String status = MediaWikiResponses.string(MediaWikiResponses.object(result, "login"), "result", "");
        if ("Success".equalsIgnoreCase(status)) {
            return;
        }
        if ("NeedToken".equalsIgnoreCase(status)) {
            String needToken = MediaWikiResponses.string(MediaWikiResponses.object(result, "login"), "token", "");
            result = loginPost("action=login&format=json&lgname=" + enc(credentials.username())
                    + "&lgpassword=" + enc(password) + "&lgtoken=" + enc(needToken));
            status = MediaWikiResponses.string(MediaWikiResponses.object(result, "login"), "result", "");
            if ("Success".equalsIgnoreCase(status)) {
                return;
            }
        }

        // Rückfall clientlogin (z. B. Wikis ohne Bot-Passwörter), mit frischer Sitzung und neuem Token.
        transport.resetSession();
        token = loginToken();
        String returnUrl = site.apiEndpoint();
        result = loginPost("action=clientlogin&format=json&loginreturnurl=" + enc(returnUrl)
                + "&username=" + enc(credentials.username()) + "&password=" + enc(password)
                + "&logintoken=" + enc(token));
        String clientStatus =
                MediaWikiResponses.string(MediaWikiResponses.object(result, "clientlogin"), "status", "");
        if ("PASS".equalsIgnoreCase(clientStatus)) {
            return;
        }
        throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "login to MediaWiki " + site.siteKey()
                + " failed (login=" + (status.isEmpty() ? "?" : status) + ", clientlogin="
                + (clientStatus.isEmpty() ? "?" : clientStatus) + ")");
    }

    private String loginToken() throws KnowledgeSourceException {
        // POST, damit das hier gesetzte Session-Cookie dasselbe ist wie beim Login (MainframeMate-Erfahrung).
        String token = MediaWikiResponses.loginToken(postRaw("action=query&meta=tokens&type=login&format=json"));
        if (token.isEmpty()) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED,
                    "MediaWiki " + site.siteKey() + " returned no login token");
        }
        return token;
    }

    private JsonObject loginPost(String body) throws KnowledgeSourceException {
        // Fehlerart des Parsers bleibt erhalten (z. B. readonly/maxlag = UNAVAILABLE); die Meldung enthält
        // keinen Request-Körper.
        return MediaWikiResponses.parseObject(postRaw(body));
    }

    private String postRaw(String body) throws KnowledgeSourceException {
        try {
            return requireOk(transport.postForm(body));
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    // ── Hilfen ──────────────────────────────────────────────────────────────────────────────

    static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported", e);
        }
    }

    private static String join(List<String> titles) {
        StringBuilder sb = new StringBuilder();
        for (String title : titles) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(title);
        }
        return sb.toString();
    }
}
