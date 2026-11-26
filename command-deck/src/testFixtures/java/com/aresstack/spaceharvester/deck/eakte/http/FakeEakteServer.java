package com.aresstack.spaceharvester.deck.eakte.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nachbau der E-Akte samt Einmalanmeldung für Tests und Bildschirmfotos, nach dem Protokoll des Testprogramms von
 * Angelo (23.11.2026): drei Server auf dem eigenen Rechner,
 * <ul>
 *   <li><b>E-Akte</b> (Fabasoft): leitet ohne Sitzung mit {@code SAMLRequest} zu OpenAM, nimmt die SAML-Antwort unter
 *       {@code /fsc/saml2/post} an, setzt die Sitzung und beantwortet PROPFIND/GET unter {@code /fsc/webdav}. Wie die
 *       echte E-Akte gilt eine Anfrage mit mehreren {@code Cookie}-Zeilen als nicht angemeldet (v1 des Testprogramms
 *       bekam deshalb 302).</li>
 *   <li><b>OpenAM</b>: Zwischenseite „Access rights validated“ mit {@code secondVisitUrl}/{@code loginUrl}, REST
 *       {@code /openam/json/authenticate} mit {@code RedirectCallback} und Fortsetzung über {@code code}/{@code state},
 *       selbstabschickendes Formular mit {@code SAMLResponse}.</li>
 *   <li><b>Identitätsdienst</b>: OAuth-{@code authorize}, Weiterleitungen und das Anmeldeformular
 *       {@code doLogin}/{@code uid}/{@code password}.</li>
 * </ul>
 * Alle Daten sind erfunden. Mit {@link #separateWebDavSession(boolean)} verlangt WebDAV eine eigene SAML-Runde (dann muss
 * der Client sie mit der bestehenden OpenAM-Sitzung abschließen).
 */
public final class FakeEakteServer implements AutoCloseable {

    public static final String USER = "pilot";
    public static final String PASSWORD = "sternenstaub";
    public static final String REALM = "/flotte";

    private static final String WEBDAV = "/fsc/webdav";
    private static final String ACS = "/fsc/saml2/post";
    private static final String START = "/fsc/fscasp/content/bin/fscvext.dll";

    /** Erfundene Outlook-Nachricht (264 KiB, OLE-Kopf und danach deterministische Bytes), wie die echte .msg. */
    public static final String MESSAGE_PATH = "/Arbeitsvorrat/Vorgang 4711/Anfrage Frachtraum #7 (Kopie).msg";
    public static final String MESSAGE_TYPE = "application/vnd.ms-outlook";
    public static final String MESSAGE_ADDRESS = "COO.1.2.3.4711";
    public static final byte[] MESSAGE = message();
    /** Mappe ohne Berechtigung (PROPFIND → 403). */
    public static final String FORBIDDEN_FOLDER = "/Gesperrt/";
    /** Datei, deren Abruf mit einem Serverfehler endet (GET → 500). */
    public static final String BROKEN_FILE = "/Ablage/Defekt.txt";

    /** Erfundener Akteninhalt: Pfad unter /fsc/webdav → Inhalt; Mappen enden auf „/“. */
    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("/", "");
        FILES.put("/Arbeitsvorrat/", "");
        FILES.put("/Arbeitsvorrat/Antrag Frachtraum 7.txt", "Antrag auf Zuweisung von Frachtraum 7.\n");
        FILES.put("/Arbeitsvorrat/Vermerk Bergung.txt", "Vermerk: Bergung abgeschlossen, Ladung gesichert.\n");
        FILES.put("/Ablage/", "");
        FILES.put("/Ablage/Logbuch 2026.txt", "Logbuch der Avenger, Eintrag 1.\n");
        FILES.put("/Übersicht Akten.txt", "Übersicht über alle erfundenen Akten.\n");
        FILES.put("/Arbeitsvorrat/Vorgang 4711/", "");
        FILES.put(MESSAGE_PATH, ""); // Inhalt: MESSAGE (binär)
        FILES.put("/Pinnwand/", ""); // leer
        FILES.put(FORBIDDEN_FOLDER, "");
        FILES.put(BROKEN_FILE, "");
    }

    /** Eine echte (erfundene) Outlook-Nachricht mit dem Erlass als PDF-Anlage, über 256 KB groß (gestreamt). */
    private static byte[] message() {
        return FakeOutlookMessage.build(256 * 1024);
    }

    private final HttpServer eakte;
    private final HttpServer idp;
    private final HttpServer provider;
    private final Set<String> eakteSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> davSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> idpSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> samlRequests = new ConcurrentHashMap<>(); // ReqID -> RelayState
    private final Set<String> assertions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> authIds = new ConcurrentHashMap<>(); // authId -> state
    private final Map<String, String> codes = new ConcurrentHashMap<>(); // code -> state
    private final Map<String, String> providerPending = new ConcurrentHashMap<>(); // provider session -> redirect
    private final Set<String> providerLoggedIn = ConcurrentHashMap.newKeySet();
    private final AtomicInteger passwordPosts = new AtomicInteger();
    private final List<String> loginReferers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger restPasswordAnswers = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile boolean separateWebDavSession;
    private volatile boolean nameCallbacks;
    private volatile boolean foreignFormAction;
    /** Der nächste Abruf der Nachricht reißt nach einem Viertel ab (abgerissene WLAN-Verbindung). */
    private final java.util.concurrent.atomic.AtomicBoolean breakMessage = new java.util.concurrent.atomic.AtomicBoolean();
    private final List<String> messageRanges = new CopyOnWriteArrayList<>();

    public FakeEakteServer() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        eakte = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        idp = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        provider = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        eakte.createContext("/", this::eakte);
        idp.createContext("/", this::idp);
        provider.createContext("/", this::provider);
        eakte.start();
        idp.start();
        provider.start();
    }

    /** WebDAV verlangt eine eigene SAML-Runde (eigenes Sitzungscookie mit Pfad /fsc/webdav). */
    public FakeEakteServer separateWebDavSession(boolean separate) {
        this.separateWebDavSession = separate;
        return this;
    }

    /** OpenAM fragt direkt nach Benutzer und Kennwort (NameCallback/PasswordCallback) statt umzuleiten. */
    /** Das Anmeldeformular des Identitätsdienstes zielt auf einen anderen Server (OpenAM) als die Formularseite. */
    public FakeEakteServer foreignFormAction(boolean on) {
        this.foreignFormAction = on;
        return this;
    }

    public FakeEakteServer nameCallbacks(boolean direct) {
        this.nameCallbacks = direct;
        return this;
    }

    public URI webdav() {
        return URI.create(base(eakte) + WEBDAV);
    }

    public URI start() {
        return URI.create(base(eakte) + START + "?bx=COO.1.2.3&venv_view=COO.4.5.6");
    }

    /** Der {@code Referer} jedes Kennwort-POST am Formular des Identitätsdienstes ({@code "null"} ohne). */
    public List<String> loginReferers() {
        return List.copyOf(loginReferers);
    }

    /** Wie oft ein Kennwort ans Anmeldeformular ging. */
    public int passwordPosts() {
        return passwordPosts.get();
    }

    /** Wie oft ein Kennwort über REST beantwortet wurde. */
    public int restPasswordAnswers() {
        return restPasswordAnswers.get();
    }

    /** Alle Anfragen als „Server METHODE Pfad“. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** Beendet alle Sitzungen der E-Akte (OpenAM-Sitzung bleibt). */
    /** Der nächste Abruf der Nachricht reißt nach einem Viertel ab; danach beantwortet der Server Range mit 206. */
    public FakeEakteServer breakMessageOnce() {
        breakMessage.set(true);
        return this;
    }

    /** Die {@code Range}-Kopfzeilen der Abrufe der Nachricht ({@code "-"} ohne). */
    public List<String> messageRanges() {
        return List.copyOf(messageRanges);
    }

    public void expireEakteSessions() {
        eakteSessions.clear();
        davSessions.clear();
    }

    /** Beendet auch die OpenAM-Sitzung. */
    public void expireAllSessions() {
        expireEakteSessions();
        idpSessions.clear();
    }

    // ---------------------------------------------------------------- E-Akte

    private void eakte(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add("eakte " + exchange.getRequestMethod() + " " + path);
        if (path.equals(ACS) && exchange.getRequestMethod().equals("POST")) {
            Map<String, String> form = form(exchange);
            if (!assertions.remove(form.getOrDefault("SAMLResponse", ""))) {
                send(exchange, 403, "text/html", "<html><title>Fehler</title>SAML ungültig</html>");
                return;
            }
            String relay = form.getOrDefault("RelayState", START);
            String session = UUID.randomUUID().toString();
            if (relay.startsWith(WEBDAV) && separateWebDavSession) {
                davSessions.add(session);
                exchange.getResponseHeaders().add("Set-Cookie", "FSCDAV=" + session + "; Path=" + WEBDAV
                        + "; HttpOnly");
            } else {
                eakteSessions.add(session);
                exchange.getResponseHeaders().add("Set-Cookie", "FSCSESSION=" + session + "; Path=/fsc; HttpOnly");
                exchange.getResponseHeaders().add("Set-Cookie", "FSCLANG=de; Path=/fsc");
            }
            redirect(exchange, base(eakte) + relay);
            return;
        }
        boolean dav = path.startsWith(WEBDAV);
        if (!signedIn(exchange, dav)) {
            String relay = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            String req = UUID.randomUUID().toString();
            samlRequests.put(req, relay);
            redirect(exchange, base(idp) + "/openam/SSORedirect/metaAlias/flotte/_IDP?SAMLRequest=" + req
                    + "&RelayState=" + enc(relay) + "&SigAlg=rsa&Signature=sig");
            return;
        }
        if (path.equals(START)) {
            exchange.getResponseHeaders().add("Set-Cookie", "FSCVIEW=1; Path=/fsc/fscasp");
            send(exchange, 200, "text/html; charset=utf-8", "<html><head><title>E-Akte</title><script></script>"
                    + "</head><body>Willkommen</body></html>");
            return;
        }
        if (!dav) {
            send(exchange, 404, "text/plain", "nicht gefunden");
            return;
        }
        String rel = URLDecoder.decode(path.substring(WEBDAV.length()), StandardCharsets.UTF_8);
        if (rel.isEmpty()) {
            rel = "/";
        }
        String key = FILES.containsKey(rel) ? rel : FILES.containsKey(rel + "/") ? rel + "/" : null;
        if (key == null) {
            send(exchange, 404, "text/plain", "nicht gefunden");
            return;
        }
        if (key.equals(FORBIDDEN_FOLDER) || key.equals(BROKEN_FILE) && exchange.getRequestMethod().equals("GET")) {
            exchange.getRequestBody().readAllBytes();
            boolean forbidden = key.equals(FORBIDDEN_FOLDER);
            send(exchange, forbidden ? 403 : 500, "text/plain", forbidden ? "verboten" : "kaputt");
            return;
        }
        if (exchange.getRequestMethod().equals("PROPFIND")) {
            exchange.getRequestBody().readAllBytes();
            boolean deep = !"0".equals(exchange.getRequestHeaders().getFirst("Depth"));
            exchange.getResponseHeaders().add("Set-Cookie", "FSCDAVSEEN=1; Path=" + WEBDAV);
            send(exchange, 207, "application/xml; charset=utf-8", multistatus(key, deep));
            return;
        }
        if (exchange.getRequestMethod().equals("GET") && key.equals(MESSAGE_PATH)) {
            exchange.getResponseHeaders().add("Content-Type", MESSAGE_TYPE);
            exchange.getResponseHeaders().add("ETag", "\"4711-1\"");
            String range = exchange.getRequestHeaders().getFirst("Range");
            messageRanges.add(range == null ? "-" : range);
            if (breakMessage.compareAndSet(true, false)) {
                exchange.sendResponseHeaders(200, MESSAGE.length);
                OutputStream out = exchange.getResponseBody();
                out.write(MESSAGE, 0, MESSAGE.length / 4);
                out.flush();
                exchange.close();
                return;
            }
            if (range != null && range.startsWith("bytes=") && range.endsWith("-")) {
                int from = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
                exchange.getResponseHeaders().add("Content-Range",
                        "bytes " + from + "-" + (MESSAGE.length - 1) + "/" + MESSAGE.length);
                exchange.sendResponseHeaders(206, MESSAGE.length - from);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(MESSAGE, from, MESSAGE.length - from);
                }
                return;
            }
            exchange.sendResponseHeaders(200, MESSAGE.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(MESSAGE);
            }
            return;
        }
        if (exchange.getRequestMethod().equals("GET") && !key.endsWith("/")) {
            send(exchange, 200, "text/plain; charset=utf-8", FILES.get(key));
            return;
        }
        send(exchange, 405, "text/plain", "nicht erlaubt");
    }

    private boolean signedIn(HttpExchange exchange, boolean dav) {
        List<String> lines = exchange.getRequestHeaders().get("Cookie");
        if (lines == null || lines.size() != 1) {
            return false; // wie die echte E-Akte: mehrere Cookie-Zeilen gelten nicht
        }
        Map<String, String> cookies = cookies(lines.get(0));
        if (dav && separateWebDavSession) {
            return davSessions.contains(cookies.getOrDefault("FSCDAV", ""));
        }
        return eakteSessions.contains(cookies.getOrDefault("FSCSESSION", ""));
    }

    private String multistatus(String key, boolean deep) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">");
        xml.append(response(key));
        if (deep && key.endsWith("/")) {
            for (String candidate : FILES.keySet()) {
                if (!candidate.equals(key) && candidate.startsWith(key)) {
                    String rest = candidate.substring(key.length());
                    int slash = rest.indexOf('/');
                    if (slash < 0 || slash == rest.length() - 1) {
                        xml.append(response(candidate));
                    }
                }
            }
        }
        return xml.append("</d:multistatus>").toString();
    }

    private static String response(String key) {
        boolean folder = key.endsWith("/");
        StringBuilder href = new StringBuilder(WEBDAV);
        for (String segment : key.split("/")) {
            if (!segment.isEmpty()) {
                href.append('/').append(enc(segment).replace("+", "%20"));
            }
        }
        if (folder) {
            href.append('/');
        }
        if (key.equals(MESSAGE_PATH)) {
            // Wie Fabasoft laut Herstellerdoku: objaddress im eigenen Namensraum, nicht gelieferte Eigenschaft als 404
            return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop><d:resourcetype/>"
                    + "<d:iscollection>0</d:iscollection><d:getcontentlength>" + MESSAGE.length
                    + "</d:getcontentlength><d:getcontenttype>" + MESSAGE_TYPE + "</d:getcontenttype>"
                    + "<d:creationdate>2026-11-20T08:15:00Z</d:creationdate><d:getetag>\"4711-1\"</d:getetag>"
                    + "<f:COOSYSTEM_1_1_objaddress xmlns:f=\"urn:fabasoft:erfunden\">" + MESSAGE_ADDRESS
                    + "</f:COOSYSTEM_1_1_objaddress><d:getlastmodified>Mon, 23 Nov 2026 15:00:00 GMT</d:getlastmodified>"
                    + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop>"
                    + "<d:displayname>falsch</d:displayname></d:prop><d:status>HTTP/1.1 404 Not Found</d:status>"
                    + "</d:propstat></d:response>";
        }
        String content = FILES.get(key);
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>"
                + (folder ? key.equals("/Arbeitsvorrat/Vorgang 4711/")
                ? "<d:resourcetype/><d:iscollection>1</d:iscollection>" // nur iscollection, wie manche Fabasoft-Objekte
                : "<d:resourcetype><d:collection/></d:resourcetype>"
                : "<d:resourcetype/><d:getcontentlength>" + content.getBytes(StandardCharsets.UTF_8).length
                + "</d:getcontentlength><d:getcontenttype>text/plain</d:getcontenttype>")
                + "<d:getlastmodified>Mon, 23 Nov 2026 15:00:00 GMT</d:getlastmodified>"
                + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    // ---------------------------------------------------------------- OpenAM

    private void idp(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        requests.add("openam " + exchange.getRequestMethod() + " " + path);
        Map<String, String> query = query(uri.getRawQuery());
        boolean session = idpSessions.contains(cookies(exchange).getOrDefault("iPlanetDirectoryPro", ""));
        if (path.startsWith("/openam/SSORedirect/")) {
            String req = query.containsKey("ReqID") ? query.get("ReqID") : query.get("SAMLRequest");
            String relay = samlRequests.get(req == null ? "" : req);
            if (relay == null) {
                send(exchange, 400, "text/html", "<html><title>Fehler</title>unbekannte Anforderung</html>");
                return;
            }
            if (session && query.containsKey("ReqID")) {
                String assertion = UUID.randomUUID().toString();
                assertions.add(assertion);
                send(exchange, 200, "text/html;charset=UTF-8", "<html><body onload=\"document.forms[0].submit()\">"
                        + "<form method=\"post\" action=\"" + base(eakte) + ACS + "\">"
                        + "<input type=\"hidden\" name=\"SAMLResponse\" value=\"" + assertion + "\"/>"
                        + "<input type=\"hidden\" name=\"RelayState\" value=\"" + relay + "\"/>"
                        + "<input type=\"submit\" value=\"Weiter\"/></form></body></html>");
                return;
            }
            exchange.getResponseHeaders().add("Set-Cookie", "amlbcookie=01; Path=/");
            String second = "/openam/SSORedirect/metaAlias/flotte/_IDP?ReqID=" + req + "&index=null&acsURL="
                    + enc(base(eakte) + ACS) + "&spEntityID=eakte&binding=post";
            String login = "/openam/UI/Login?realm=" + enc(REALM) + "&spEntityID=eakte&goto=x&AMAuthCookie=y";
            send(exchange, 200, "text/html;charset=UTF-8", "<html><head><title>Access rights validated</title>"
                    + "</head><body><form method=\"post\"><input type=\"hidden\" id=\"saml2Request\" value=\"r\"/>"
                    + "<input type=\"hidden\" id=\"secondVisitUrl\" value=\"" + second.replace("&", "&amp;") + "\"/>"
                    + "<input type=\"hidden\" id=\"loginUrl\" value=\"" + login.replace("&", "&amp;") + "\"/>"
                    + "</form><script>/* weiter */</script></body></html>");
            return;
        }
        if (path.equals("/openam/json/authenticate") && exchange.getRequestMethod().equals("POST")) {
            authenticate(exchange, query);
            return;
        }
        if (path.equals("/openam/")) {
            // Die feste Startseite von OpenAM nach der Rückkehr vom Identitätsdienst (wie im Selbsttest von v2.0).
            send(exchange, 200, "text/html", "<html><head><title>Please Wait While Redirecting to Login page</title>"
                    + "<script>function redirectToAuth(){ var params=getQueryParameters(); "
                    + "var url = 'UI/Login'; if(params != ''){url += params;} "
                    + "top.location.replace(url); } "
                    + "function getQueryParameters() {return location.search;}</script></head>"
                    + "<body onLoad='redirectToAuth();'></body></html>");
            return;
        }
        if (path.equals("/openam/UI/Login")) {
            redirect(exchange, base(idp) + "/openam/XUI/?" + uri.getRawQuery());
            return;
        }
        if (path.startsWith("/openam/")) {
            send(exchange, 200, "text/html", "<html><head><title>Bundescloud</title><script></script><script>"
                    + "</script></head><body></body></html>");
            return;
        }
        send(exchange, 404, "text/plain", "nicht gefunden");
    }

    private void authenticate(HttpExchange exchange, Map<String, String> query) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Set-Cookie", "authtrack=1; Path=/");
        if (nameCallbacks) {
            if (body.equals("{}")) {
                send(exchange, 200, "application/json", "{\"authId\":\"A1\",\"callbacks\":["
                        + "{\"type\":\"NameCallback\",\"output\":[{\"name\":\"prompt\",\"value\":\"Benutzer\"}],"
                        + "\"input\":[{\"name\":\"IDToken1\",\"value\":\"\"}]},"
                        + "{\"type\":\"PasswordCallback\",\"output\":[{\"name\":\"prompt\",\"value\":\"Kennwort\"}],"
                        + "\"input\":[{\"name\":\"IDToken2\",\"value\":\"\"}]}]}");
                return;
            }
            restPasswordAnswers.incrementAndGet();
            if (body.contains("\"" + USER + "\"") && body.contains("\"" + PASSWORD + "\"")) {
                sendToken(exchange);
            } else {
                send(exchange, 401, "application/json", "{\"code\":401,\"reason\":\"Unauthorized\"}");
            }
            return;
        }
        if (query.containsKey("code")) {
            String state = codes.remove(query.get("code"));
            String authId = body.replaceAll("(?s).*\"authId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            if (state == null || !state.equals(query.get("state")) || !state.equals(authIds.remove(authId))) {
                send(exchange, 401, "application/json", "{\"code\":401}");
                return;
            }
            sendToken(exchange);
            return;
        }
        String authId = "auth-" + UUID.randomUUID();
        String state = UUID.randomUUID().toString();
        authIds.put(authId, state);
        String redirectUrl = base(provider) + "/isam/sps/oauth/oauth20/authorize?nonce=n&response_type=code"
                + "&client_id=openam&redirect_uri=" + enc(base(idp) + "/openam/") + "&scope=openid"
                + "&code_challenge=c&code_challenge_method=S256&state=" + state;
        send(exchange, 200, "application/json", "{\"authId\":\"" + authId + "\",\"callbacks\":[{\"type\":"
                + "\"RedirectCallback\",\"output\":[{\"name\":\"redirectUrl\",\"value\":\"" + redirectUrl + "\"},"
                + "{\"name\":\"redirectMethod\",\"value\":\"GET\"},{\"name\":\"trackingCookie\",\"value\":true}]}]}");
    }

    private void sendToken(HttpExchange exchange) throws IOException {
        String token = "AQIC" + UUID.randomUUID().toString().replace("-", "") + "*";
        idpSessions.add(token);
        send(exchange, 200, "application/json", "{\"tokenId\":\"" + token + "\",\"successUrl\":\"/openam/console\","
                + "\"realm\":\"" + REALM + "\"}");
    }

    // ---------------------------------------------------------------- Identitätsdienst

    private void provider(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        requests.add("provider " + exchange.getRequestMethod() + " " + path);
        String session = cookies(exchange).get("PD-S-SESSION-ID");
        if (path.equals("/isam/sps/oauth/oauth20/authorize")) {
            Map<String, String> query = query(uri.getRawQuery());
            if (session == null) {
                session = UUID.randomUUID().toString();
                exchange.getResponseHeaders().add("Set-Cookie", "PD-S-SESSION-ID=" + session + "; Path=/");
                exchange.getResponseHeaders().add("Set-Cookie", "PD-ID=x; Path=/");
            }
            providerPending.put(session, query.get("redirect_uri") + "?realm=" + enc(REALM) + "&service=oauth&state="
                    + query.get("state"));
            redirect(exchange, base(provider) + "/isam/sps/auth");
            return;
        }
        if (path.equals("/isam/sps/auth")) {
            String pending = session == null ? null : providerPending.get(session);
            if (pending == null) {
                send(exchange, 400, "text/html", "<html><title>Fehler</title></html>");
                return;
            }
            if (!providerLoggedIn.contains(session)) {
                redirect(exchange, base(provider) + "/eai/mapz/login?TAM_OP=login");
                return;
            }
            String state = query(URI.create(pending).getRawQuery()).get("state");
            String code = UUID.randomUUID().toString();
            codes.put(code, state);
            redirect(exchange, pending + "&code=" + code + "&iss=" + enc(base(provider)));
            return;
        }
        if (path.equals("/eai/mapz/login")) {
            if (exchange.getRequestMethod().equals("POST")) {
                passwordPosts.incrementAndGet();
                loginReferers.add(String.valueOf(exchange.getRequestHeaders().getFirst("Referer")));
                Map<String, String> form = form(exchange);
                if (USER.equals(form.get("uid")) && PASSWORD.equals(form.get("password"))
                        && "Anmelden".equals(form.get("doLogin")) && session != null) {
                    providerLoggedIn.add(session);
                    redirect(exchange, base(provider) + "/isam/sps/auth");
                    return;
                }
            }
            send(exchange, 200, "text/html", "<html><head><title>Anmelden</title><script></script></head><body>"
                    + "<form id=\"loginForm\" method=\"post\" action=\"" + (foreignFormAction ? base(idp) : "")
                    + "/eai/mapz/login\">"
                    + "<input id=\"username\" name=\"uid\" type=\"text\"/>"
                    + "<input id=\"pw\" name=\"password\" type=\"password\"/>"
                    + "<input type=\"checkbox\" name=\"merken\"/>"
                    + "<input type=\"submit\" name=\"doLogin\" value=\"Anmelden\"/>"
                    + "<input type=\"reset\" name=\"leeren\" value=\"Leeren\"/>"
                    + "</form></body></html>");
            return;
        }
        send(exchange, 404, "text/plain", "nicht gefunden");
    }

    // ---------------------------------------------------------------- Hilfen

    private static void redirect(HttpExchange exchange, String target) throws IOException {
        exchange.getResponseHeaders().add("Location", target);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> cookies(HttpExchange exchange) {
        List<String> lines = exchange.getRequestHeaders().get("Cookie");
        Map<String, String> all = new LinkedHashMap<>();
        if (lines != null) {
            lines.forEach(line -> all.putAll(cookies(line)));
        }
        return all;
    }

    private static Map<String, String> cookies(String line) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String part : line.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                cookies.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return cookies;
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        return query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            values.put(key, eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String base(HttpServer server) {
        return "http://" + authority(server);
    }

    private static String authority(HttpServer server) {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        eakte.stop(0);
        idp.stop(0);
        provider.stop(0);
    }
}
