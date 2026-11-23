package com.aresstack.spaceharvester.deck.eakte.http;

import com.aresstack.spaceharvester.deck.eakte.EakteConnection;
import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Eine angemeldete E-Akte-Verbindung über WebDAV. Leitet die E-Akte eine Anfrage zur Anmeldung um, wird die
 * SAML-Anforderung mit der bestehenden OpenAM-Sitzung abgeschlossen und die Anfrage einmal wiederholt (so auch das
 * Testprogramm v2.0); ein Kennwort wird dabei nie gesendet.
 */
final class HttpEakteConnection implements EakteConnection {

    private static final int MAX_LOCAL_REDIRECTS = 5;

    private final SsoHttp http;
    private final SamlNavigator navigator;
    private final URI root;

    HttpEakteConnection(SsoHttp http, SamlNavigator navigator) {
        this.http = http;
        this.navigator = navigator;
        this.root = http.profile().webdav();
    }

    @Override
    public String label() {
        return http.profile().label();
    }

    @Override
    public String rootHref() {
        String path = root.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    /** Erster PROPFIND auf die Wurzel; die Anmeldung darf dabei noch das Kennwort nutzen. */
    void check(SamlNavigator withPassword) throws IOException {
        SsoHttp.Response response = request(withPassword, "PROPFIND", root, "0");
        if (response.status() != 207) {
            throw rejected(response, "WebDAV");
        }
        http.trace("WebDAV erreichbar: HTTP 207, " + WebDavXml.count(response.body()) + " DAV:response-Einträge");
    }

    @Override
    public List<EakteEntry> list(String href) throws IOException {
        SsoHttp.Response response = request(navigator, "PROPFIND", resolve(href), "1");
        if (response.status() != 207) {
            throw rejected(response, "Mappe");
        }
        return WebDavXml.entries(response.body(), href);
    }

    @Override
    public byte[] read(String href) throws IOException {
        SsoHttp.Response response = request(navigator, "GET", resolve(href), null);
        if (response.status() / 100 != 2) {
            throw rejected(response, "Dokument");
        }
        return response.body();
    }

    @Override
    public void close() {
        // Die Sitzung lebt nur in diesem Objekt (Cookies im Speicher); nichts zu trennen.
    }

    private SsoHttp.Response request(SamlNavigator sso, String method, URI target, String depth) throws IOException {
        http.trace("Cookies für " + SsoHttp.safe(target) + ": " + http.cookies().describe(target)
                + " (eine Cookie-Zeile)");
        SsoHttp.Response response = send(method, target, depth);
        for (int round = 0; round < 2 && sso.leadsToLogin(response); round++) {
            http.trace("Die E-Akte leitet " + method + " zur Anmeldung um; schließe die SAML-Anforderung mit der "
                    + "bestehenden Sitzung ab und wiederhole");
            sso.settle(response);
            response = send(method, target, depth);
        }
        if (sso.leadsToLogin(response)) {
            throw new IOException("Die E-Akte leitet " + method + " trotz abgeschlossener Anmeldung zur Anmeldung um "
                    + "(gesendete Cookies: " + http.cookies().describe(target) + ")");
        }
        return response;
    }

    /** Eine Anfrage; Weiterleitungen innerhalb der E-Akte (etwa auf „/“ am Ende) werden mit derselben Methode gefolgt. */
    private SsoHttp.Response send(String method, URI target, String depth) throws IOException {
        URI current = target;
        SsoHttp.Response response = null;
        for (int hop = 0; hop <= MAX_LOCAL_REDIRECTS; hop++) {
            byte[] body = "PROPFIND".equals(method) ? WebDavXml.PROPFIND.getBytes(StandardCharsets.UTF_8) : null;
            Map<String, String> headers = depth == null ? Map.of()
                    : Map.of("Depth", depth, "Content-Type", "application/xml; charset=utf-8",
                            "Accept", "application/xml");
            response = http.send(method, current, headers, body);
            if (!response.redirect() || !http.profile().isEakte(response.next())) {
                return response;
            }
            current = response.next();
        }
        return response;
    }

    private URI resolve(String href) {
        return root.resolve(WebDavXml.path(href));
    }

    private static IOException rejected(SsoHttp.Response response, String what) {
        return switch (response.status()) {
            case 401, 403 -> new IOException(what + ": Zugriff verweigert (HTTP " + response.status() + ")");
            case 404 -> new IOException(what + " nicht gefunden (HTTP 404)");
            default -> new IOException(what + ": Die E-Akte antwortet HTTP " + response.status());
        };
    }
}
