package com.aresstack.spaceharvester.deck.eakte.http;

import com.aresstack.enterpriseai.proasteion.platform.network.ResumableDownload;
import com.aresstack.spaceharvester.deck.eakte.EakteConnection;
import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Eine angemeldete E-Akte-Verbindung über WebDAV, nur lesend ({@code PROPFIND Depth: 0/1} und {@code GET}). Leitet die E-Akte eine Anfrage zur Anmeldung um, wird die
 * SAML-Anforderung mit der bestehenden OpenAM-Sitzung abgeschlossen und die Anfrage einmal wiederholt (so auch das
 * Testprogramm v2.0); ein Kennwort wird dabei nie gesendet. Reißt ein Dokument beim Laden ab, wird es mit
 * {@link ResumableDownload} ab dem letzten Byte fortgesetzt, höchstens {@code resumes} Mal (Angelo 26.11.2026, WLAN).
 */
final class HttpEakteConnection implements EakteConnection {

    private static final int MAX_LOCAL_REDIRECTS = 5;

    private final SsoHttp http;
    private final SamlNavigator navigator;
    private final URI root;
    private final java.util.function.IntSupplier resumes;

    HttpEakteConnection(SsoHttp http, SamlNavigator navigator) {
        this(http, navigator, () -> 0);
    }

    /** @param resumes wie oft ein abgerissenes Dokument je Abruf höchstens fortgesetzt wird, 0 für nie */
    HttpEakteConnection(SsoHttp http, SamlNavigator navigator, java.util.function.IntSupplier resumes) {
        this.http = http;
        this.navigator = navigator;
        this.root = http.profile().webdav();
        this.resumes = java.util.Objects.requireNonNull(resumes, "resumes");
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

    /**
     * Erster {@code PROPFIND Depth: 1} auf die Wurzel wie in v2.0. {@code sso} schließt einen WebDAV-Challenge ab: nach
     * der Startseite nur mit der bestehenden Sitzung, ohne Startseite darf er noch das Kennwort nutzen.
     */
    void check(SamlNavigator sso) throws IOException {
        SsoHttp.Response response = request(sso, "PROPFIND", root, "1");
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
    public long download(String href, java.io.OutputStream target) throws IOException {
        URI uri = resolve(href);
        SsoHttp.Stream stream = http.stream(uri);
        for (int round = 0; round < 2 && navigator.leadsToLogin(stream.head()); round++) {
            stream.body().close();
            http.trace("Die E-Akte leitet GET zur Anmeldung um; schließe die SAML-Anforderung mit der bestehenden "
                    + "Sitzung ab und wiederhole");
            navigator.challenge(stream.head());
            stream = http.stream(uri);
        }
        for (int hop = 0; hop < MAX_LOCAL_REDIRECTS && stream.redirect()
                && http.profile().isEakte(stream.next()); hop++) {
            stream.body().close();
            stream = http.stream(stream.next());
        }
        try (java.io.InputStream body = stream.body()) {
            if (navigator.leadsToLogin(stream.head())) {
                throw new com.aresstack.spaceharvester.deck.eakte.SessionExpiredException(
                        "Anmeldung nötig: Die E-Akte leitet das Dokument zur Anmeldung um");
            }
            if (stream.status() / 100 != 2) {
                throw rejected(stream.head(), "Dokument");
            }
            long copied = stream.status() == 200 ? resumed(stream, target) : body.transferTo(target);
            http.trace("Dokument geladen: " + copied + " Byte (" + stream.contentType() + ")");
            return copied;
        }
    }

    /** Kopiert das Dokument und fordert nach einem Abriss den Rest mit derselben Sitzung ab dem letzten Byte an. */
    private long resumed(SsoHttp.Stream first, java.io.OutputStream target) throws IOException {
        final URI at = first.uri();
        return ResumableDownload.copy(response(first), (from, validator) -> {
            http.trace("Dokument abgerissen; setze ab Byte " + from + " fort");
            return response(http.stream(at, ResumableDownload.rangeHeaders(from, validator)));
        }, ResumableDownload.sink(target), Long.MAX_VALUE, Math.max(0, resumes.getAsInt()));
    }

    private static ResumableDownload.Response response(SsoHttp.Stream stream) {
        return ResumableDownload.response(stream.status(),
                name -> stream.headers().firstValue(name).orElse(null), stream.body());
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
            sso.challenge(response);
            response = send(method, target, depth);
        }
        if (sso.leadsToLogin(response)) {
            throw new com.aresstack.spaceharvester.deck.eakte.SessionExpiredException("Anmeldung nötig: Die E-Akte "
                    + "leitet " + method + " trotz abgeschlossener Anmeldung zur Anmeldung um (gesendete Cookies: "
                    + http.cookies().describe(target) + ")");
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

    /** Verständliche Fehler: 401 Anmeldung nötig, 403 keine Berechtigung, 404 verschwunden, 5xx Serverfehler. */
    static IOException rejected(SsoHttp.Response response, String what) {
        int status = response.status();
        if (status == 401) {
            return new com.aresstack.spaceharvester.deck.eakte.SessionExpiredException(
                    "Anmeldung nötig: Die E-Akte verlangt eine neue Anmeldung (HTTP 401)");
        }
        if (status == 403) {
            return new IOException(what + ": keine Berechtigung (HTTP 403)");
        }
        if (status == 404 || status == 410) {
            return new IOException(what + " gibt es nicht mehr (HTTP " + status + ")");
        }
        if (status >= 500) {
            return new IOException(what + ": Serverfehler der E-Akte (HTTP " + status + ")");
        }
        return new IOException(what + ": Die E-Akte antwortet HTTP " + status);
    }
}
