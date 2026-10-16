package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.DownloadAccess;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Confluence-Data-Center-REST-Aufrufe (paketintern). Endpunkte und Felder aus MainframeMate
 * {@code ConfluenceRestClient} übernommen; JSON über Gson statt Jackson.
 *
 * <p>HTTP-Status werden auf {@link Kind} abgebildet: 401/403 und Weiterleitungen (Login-Seite) →
 * {@code ACCESS_DENIED}, 404 → {@code NOT_FOUND}, 429/5xx und Netzfehler → {@code UNAVAILABLE}, alles andere →
 * {@code INVALID_RESPONSE}. Meldungen nennen Vorgang und Status, nie Antwortkörper oder Header.
 */
final class ConfluenceRestClient {

    private static final Logger LOG = Logger.getLogger(ConfluenceRestClient.class.getName());

    /** {@code Accept} beim Bildrequest (statt {@code application/json} der REST-Aufrufe). */
    static final String IMAGE_ACCEPT = "image/*,*/*;q=0.8";

    private static final String PAGE_EXPAND = "body.view,version,space,ancestors,metadata.labels";
    /**
     * Für die Lese-Ansicht zusätzlich {@code body.export_view}: Confluence rendert dabei Makros, die
     * {@code body.view} nur als Lade-Platzhalter liefert und erst im Browser per Script füllt (Jira-Vorgänge und
     * Jira-Tabellen: „Getting issue details...“, nur die Kopfzeile). {@code body.view} bleibt Rückfall, falls
     * {@code export_view} leer ist; der Index liest weiter {@code body.view}. {@code body.storage} liefert die
     * draw.io-Makros mit ihren Parametern, auch wenn {@code export_view} nur das Vorschaubild ohne Diagrammdaten
     * rendert.
     */
    private static final String READING_EXPAND = "body.export_view,body.storage," + PAGE_EXPAND;
    /** Felder für Seiten ohne Inhalt (Startpunkte, Kindseiten, Suchtreffer); Labels sind nicht Standard. */
    private static final String LIST_EXPAND = "version,space,metadata.labels";
    /** Confluence Data Center liefert Zeitstempel auch mit kompaktem Offset ({@code +1100}), kein ISO-8601. */
    private static final DateTimeFormatter COMPACT_OFFSET =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss[.SSS]XX", Locale.ROOT);

    /** So viele Diagnosedateien schreibt eine Quelle je Programmlauf höchstens. */
    static final int MAX_DIAGNOSTIC_FILES = 5;

    private final ConfluenceConfig config;
    private final ConfluenceHttpTransport transport;
    private final java.util.concurrent.atomic.AtomicInteger diagnosticFiles =
            new java.util.concurrent.atomic.AtomicInteger();
    private final ConfluenceWebSession web = new ConfluenceWebSession();
    /** Ein Formular zur Zeit: zwei Codes derselben Sitzung kreuzen sich nicht. */
    private final java.util.concurrent.locks.ReentrantLock challengeLock =
            new java.util.concurrent.locks.ReentrantLock();

    ConfluenceRestClient(ConfluenceConfig config, ConfluenceHttpTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    /** Der Space mit Startseiten-ID oder {@code null}, wenn es ihn nicht gibt. */
    Space space(Session session, String spaceKey) throws KnowledgeSourceException {
        JsonObject space;
        try {
            space = getJson(session, "/rest/api/space/" + pathSegment(spaceKey) + "?expand=homepage", "Space");
        } catch (KnowledgeSourceException e) {
            if (e.kind() == Kind.NOT_FOUND) {
                return null;
            }
            throw e;
        }
        JsonObject home = object(space, "homepage");
        return new Space(spaceKey, string(space, "name"), home == null ? null : string(home, "id"));
    }

    /**
     * Alle Spaces (wie MainframeMate, auch persönliche) mit Schlüssel, Namen und Startseiten-ID (MainframeMate
     * {@code SpaceItem.homepageId}), in Confluence-Reihenfolge.
     */
    List<Space> spaces(Session session, int max) throws KnowledgeSourceException {
        return paged(session, "/rest/api/space?expand=homepage", "Spaces", max, new Item<Space>() {
            @Override
            public Space map(JsonObject space) {
                String key = string(space, "key");
                if (key == null || key.trim().isEmpty()) {
                    return null;
                }
                JsonObject home = object(space, "homepage");
                return new Space(key.trim(), string(space, "name"), home == null ? null : string(home, "id"));
            }
        });
    }

    /** Die Content-ID der Seite mit genau diesem Titel im Space oder {@code null}, wenn es sie nicht gibt. */
    String pageIdByTitle(Session session, String spaceKey, String title) throws KnowledgeSourceException {
        JsonObject json = getJson(session, "/rest/api/content?type=page&spaceKey=" + query(spaceKey) + "&title="
                + query(title) + "&limit=1", "Seite nach Titel");
        for (JsonObject page : results(json, "Seite nach Titel")) {
            String id = string(page, "id");
            if (ConfluenceIds.isContentId(id)) {
                return id;
            }
        }
        return null;
    }

    /** Alle Seiten eines Space ohne Inhalt, höchstens {@code max}, in Confluence-Reihenfolge. */
    List<Page> spacePages(Session session, String spaceKey, int max) throws KnowledgeSourceException {
        return paged(session, "/rest/api/content?type=page&spaceKey=" + query(spaceKey) + "&expand=" + LIST_EXPAND
                + ",ancestors", "Seiten des Space", max, new Item<Page>() {
                    @Override
                    public Page map(JsonObject json) throws KnowledgeSourceException {
                        return toPage(json);
                    }
                });
    }

    /**
     * @param withBody {@code true} lädt den gerenderten Inhalt ({@code body.view}) mit
     * @throws KnowledgeSourceException {@code NOT_FOUND}, wenn die Seite nicht existiert oder keine Seite ist
     */
    Page page(Session session, String contentId, boolean withBody) throws KnowledgeSourceException {
        String expand = withBody ? PAGE_EXPAND : LIST_EXPAND + ",ancestors";
        JsonObject json = getJson(session, "/rest/api/content/" + contentId + "?expand=" + expand, "Seite");
        if (!"page".equals(string(json, "type"))) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Inhalt " + contentId + " ist keine Seite");
        }
        return toPage(json);
    }

    /** Die Seite für die Lese-Ansicht: {@code body.export_view}, leer oder fehlend {@code body.view}. */
    Page readingPage(Session session, String contentId) throws KnowledgeSourceException {
        JsonObject json = getJson(session, "/rest/api/content/" + contentId + "?expand=" + READING_EXPAND, "Seite");
        if (!"page".equals(string(json, "type"))) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Inhalt " + contentId + " ist keine Seite");
        }
        JsonObject storage = object(object(json, "body"), "storage");
        return toPage(json, true).withStorage(storage == null ? null : string(storage, "value"));
    }

    /** Direkte Kindseiten in Confluence-Reihenfolge, höchstens {@code max}. */
    List<Page> children(Session session, String contentId, int max) throws KnowledgeSourceException {
        return paged(session, "/rest/api/content/" + contentId + "/child/page?expand=" + LIST_EXPAND, "Kindseiten",
                max, new Item<Page>() {
                    @Override
                    public Page map(JsonObject json) throws KnowledgeSourceException {
                        return toPage(json);
                    }
                });
    }

    /**
     * Anhänge einer Seite, die {@code accept} bestehen, höchstens {@code max}; es wird so lange geblättert, bis
     * {@code max} passende Anhänge beisammen sind, damit unpassende keinen Platz im Budget verbrauchen.
     */
    List<Attachment> attachments(Session session, final String contentId, int max, final Predicate<Attachment> accept)
            throws KnowledgeSourceException {
        return paged(session, "/rest/api/content/" + contentId + "/child/attachment?expand=version,container",
                "Anhänge", max, new Item<Attachment>() {
                    @Override
                    public Attachment map(JsonObject json) throws KnowledgeSourceException {
                        if (attachmentId(json) == null) {
                            // Ein Eintrag ohne brauchbare ID kostet nur sich selbst, nicht die ganze Liste.
                            LOG.warning("[Confluence] Anhänge: Eintrag übersprungen, ID " + idShape(json)
                                    + " (erwartet att<Ziffern> oder Ziffern)");
                            return null;
                        }
                        Attachment attachment = toAttachment(json, contentId);
                        return accept.test(attachment) ? attachment : null;
                    }
                });
    }

    /** Ein Anhang; {@code NOT_FOUND}, wenn es ihn nicht gibt. */
    Attachment attachment(Session session, String attachmentId) throws KnowledgeSourceException {
        JsonObject json = getJson(session, "/rest/api/content/" + attachmentId + "?expand=version,container",
                "Anhang");
        if (!"attachment".equals(string(json, "type"))) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Inhalt " + attachmentId + " ist kein Anhang");
        }
        JsonObject container = object(json, "container");
        return toAttachment(json, container == null ? null : string(container, "id"));
    }

    /** Lädt den Inhalt eines Anhangs; nur Pfade unterhalb der Basis-URL. */
    byte[] download(Session session, Attachment attachment) throws KnowledgeSourceException {
        if (attachment.downloadPath == null || !attachment.downloadPath.startsWith("/")
                || attachment.downloadPath.startsWith("//")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                    "Anhang " + attachment.id + " ohne gültigen Download-Pfad");
        }
        ConfluenceHttpResponse response = send(session, attachment.downloadPath, "Anhang-Download",
                config.maxAttachmentBytes());
        return response.body();
    }

    /** {@code Accept} beim Laden von Anhängen und Vorschauen für den Lesebereich (wie der Browser im Mitschnitt). */
    static final String ANY_ACCEPT = "*/*";

    /**
     * Pfad der Confluence-Dokumentkonvertierung, wie Confluence ihn selbst in der Seite ({@code view-file}-Makro,
     * Vorschaubild) und im Files-Endpunkt ({@code previewContents}) nennt:
     * {@code /rest/documentConversion/latest/conversion/<art>/<numerische Anhang-ID>/<Version>} mit den Arten
     * {@code convert} (Dokument), {@code poster} und {@code thumbnail}.
     */
    static String conversionPath(String attachmentId, String version, String kind) {
        String numeric = attachmentId.startsWith("att") ? attachmentId.substring(3) : attachmentId;
        return "/rest/documentConversion/latest/conversion/" + kind + "/" + numeric + "/"
                + (version == null || version.isEmpty() ? "1" : version);
    }

    /**
     * Die Originaldatei eines Anhangs für den Lesebereich: wie {@link #download}, aber mit {@link #ANY_ACCEPT}, eigener
     * Grenze und einer Prüfung gegen Anmeldeseiten (HTML, wo der Anhang keins ist). Weiterleitungen auf einen anderen
     * Host folgt der Weg wie der Bildweg ohne Anmeldung; greift die Anmeldung nicht, gilt derselbe Sitzungs-Cookie-
     * Neuversuch wie für Bilder ({@link #withSessionCookie}).
     */
    byte[] readAttachment(Session session, final Attachment attachment, final int maxBytes)
            throws KnowledgeSourceException {
        if (attachment.downloadPath == null || !attachment.downloadPath.startsWith("/")
                || attachment.downloadPath.startsWith("//")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                    "Anhang " + attachment.id + " ohne gültigen Download-Pfad");
        }
        return withWebSession(session, ANY_ACCEPT, "Anhang", new Attempt() {
            @Override
            public byte[] run(Map<String, String> headers) throws KnowledgeSourceException {
                ConfluenceHttpResponse response = fetch(headers, attachment.downloadPath, "Anhang", maxBytes);
                String received = baseMediaType(response.contentType());
                if (received.contains("html") && !baseMediaType(attachment.mediaType).contains("html")) {
                    URI at = fileUri(attachment.downloadPath, response);
                    HtmlResponseDiagnosis page = diagnoseHtml("Anhang", at, response, headers);
                    throw htmlFailure(at, response, page, fileFailure("Anhang", attachment.downloadPath, response,
                            new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Anhang: erwartet "
                                    + attachment.mediaType + ", erhalten " + received
                                    + " (vermutlich eine Anmeldeseite)" + pageSuffix(page))));
                }
                return response.body();
            }
        });
    }

    /**
     * Eine Vorschau der Dokumentkonvertierung ({@link #conversionPath}); nur PDF oder Bild gilt, alles andere (etwa
     * die Anmeldeseite) wird abgelehnt, mit Sitzungs-Cookie-Neuversuch wie beim Bild. 202 heißt: Confluence erzeugt
     * sie noch.
     */
    byte[] conversion(Session session, Attachment attachment, String kind, final int maxBytes)
            throws KnowledgeSourceException {
        final String path = conversionPath(attachment.id, attachment.version, kind);
        return withWebSession(session, ANY_ACCEPT, "Vorschau", new Attempt() {
            @Override
            public byte[] run(Map<String, String> headers) throws KnowledgeSourceException {
                ConfluenceHttpResponse response = fetch(headers, path, "Vorschau", maxBytes);
                if (response.status() == 202) {
                    throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Vorschau wird von Confluence noch erzeugt");
                }
                byte[] body = response.body();
                if (!isPdf(body) && notAnImage(response.contentType(), body) != null) {
                    URI at = fileUri(path, response);
                    HtmlResponseDiagnosis page = baseMediaType(response.contentType()).contains("html")
                            ? diagnoseHtml("Vorschau", at, response, headers) : null;
                    throw htmlFailure(at, response, page, fileFailure("Vorschau", path, response,
                            new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Vorschau: erwartet PDF oder Bild, "
                                    + "erhalten " + notAnImage(response.contentType(), body) + pageSuffix(page))));
                }
                return body;
            }
        });
    }

    static boolean isPdf(byte[] data) {
        return data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
    }

    /** GET mit {@link #ANY_ACCEPT}; Fehlerstatus werden mit Logzeile (ohne Query und Header) zur Ausnahme. */
    private ConfluenceHttpResponse fetch(Map<String, String> initial, String path, String what, int maxBytes)
            throws KnowledgeSourceException {
        URI uri;
        try {
            uri = URI.create(config.baseUrl() + path);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": ungültiger Pfad");
        }
        Map<String, String> headers = initial;
        ConfluenceHttpResponse response;
        for (int hop = 0; ; hop++) {
            try {
                response = hop == 0 ? transport.get(uri, headers, maxBytes)
                        : transport.getAnonymous(uri, headers, maxBytes);
            } catch (ConfluenceResponseTooLargeException e) {
                throw fileFailure(what, uri.getRawPath(), null, new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                        what + " größer als " + maxBytes + " Bytes"));
            } catch (IOException e) {
                throw fileFailure(what, uri.getRawPath(), null, new KnowledgeSourceException(Kind.UNAVAILABLE,
                        what + ": Confluence nicht erreichbar (" + cause(e) + ")", e));
            }
            if (hop == 0) {
                web.absorb(response.cookies());
            }
            URI next = hop < 5 ? foreignRedirect(uri, response) : null;
            if (next == null) {
                break;
            }
            LOG.info("[Confluence] " + what + ": Weiterleitung von " + withoutQuery(uri.toString()) + " auf "
                    + withoutQuery(next.toString()) + ", ohne Anmeldung gefolgt");
            uri = next;
            headers = withoutAuthorization(headers);
        }
        if (!response.isSuccess()) {
            throw fileFailure(what, uri.getRawPath(), response, statusFailure(what, response.status()));
        }
        return response;
    }

    /** Logzeile zum fehlgeschlagenen Anhang bzw. zur Vorschau (Pfad ohne Query, nie Header). */
    private static KnowledgeSourceException fileFailure(String what, String path, ConfluenceHttpResponse response,
                                                        KnowledgeSourceException error) {
        LOG.warning("[Confluence] " + what + " nicht geladen: pfad=" + withoutQuery(String.valueOf(path))
                + " status=" + (response == null ? "-" : String.valueOf(response.status()))
                + " contentType=" + (response == null || response.contentType().isEmpty() ? "-"
                : response.contentType())
                + " bytes=" + (response == null ? "-" : String.valueOf(response.length()))
                + " fehler=" + error.kind() + ": " + error.getMessage());
        return error;
    }

    private static String baseMediaType(String contentType) {
        String media = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int semicolon = media.indexOf(';');
        return (semicolon < 0 ? media : media.substring(0, semicolon)).trim();
    }

    /**
     * Ein Bild der Lese-Ansicht über seinen Pfad unter der Basis-URL (wie MainframeMate {@code client.getBytes(path)},
     * dort mit {@code Accept: *}{@code /*}). Gesendet wird {@link #IMAGE_ACCEPT} statt {@code application/json}.
     * Als Bild gilt eine Antwort mit {@code image/*} oder mit Bild-Signatur im Körper (PNG, JPEG, GIF, BMP, WebP,
     * SVG), etwa bei {@code application/octet-stream}; alles andere (JSON, HTML-Anmeldeseite) wird unmittelbar nach
     * HTTP mit dem erhaltenen Content-Type abgelehnt. Jeder Fehlschlag schreibt eine Logzeile mit URL, Status,
     * Content-Type, Größe und Fehlerart, nie Header.
     */
    byte[] viewImage(Session session, String path) throws KnowledgeSourceException {
        int hash = path == null ? -1 : path.indexOf('#');
        String fragment = hash < 0 ? null : path.substring(hash + 1);
        String preview = hash < 0 ? path : path.substring(0, hash);
        String drawio = null;
        if (fragment != null && fragment.startsWith(ConfluencePageView.DRAWIO_FRAGMENT + "=")
                && (fragment + "&").contains("&" + ConfluencePageView.DRAWIO_VIEW_ORIGINAL + "&")) {
            // Umschalter „Original“ im Bild-Viewer: nur die von draw.io erzeugte PNG-Vorschau, keine Zeichnung.
            LOG.info("[Confluence] draw.io: Original-Vorschau angefordert: " + withoutQuery(preview));
            return previewImage(session, preview);
        }
        if (fragment != null && fragment.startsWith(ConfluencePageView.DRAWIO_FRAGMENT + "=")) {
            // draw.io-Diagramm: erst die Zeichnung selbst (REST des Plugins, mxfile → SVG), sonst die PNG-Vorschau.
            try {
                return drawioSvg(session, fragment);
            } catch (KnowledgeSourceException | DrawioDiagramRenderer.Unsupported e) {
                drawio = e.getMessage();
                LOG.info("[Confluence] draw.io: JGraphX nicht möglich (" + drawio + "), verwende PNG-Vorschau: "
                        + withoutQuery(preview == null ? "" : preview));
            }
        }
        try {
            return previewImage(session, preview);
        } catch (TwoFactorRequired required) {
            // Keep the challenge type: only a TwoFactorRequired becomes the one-time code prompt (issue #104).
            throw drawio == null ? required : required.withDetail("; draw.io-Zeichnung: " + drawio);
        } catch (KnowledgeSourceException e) {
            if (drawio == null) {
                throw e;
            }
            throw new KnowledgeSourceException(e.kind(), e.getMessage() + "; draw.io-Zeichnung: " + drawio, e);
        }
    }

    /**
     * Das draw.io-Diagramm als SVG: {@code /rest/drawio/1.0/diagram/crud/<Name>/<Seite>?revision=<n>} (wie der
     * Browser) liefert JSON mit {@code xml} ({@code mxfile}); {@link DrawioDiagramRenderer} zeichnet es.
     */
    private byte[] drawioSvg(Session session, String fragment)
            throws KnowledgeSourceException, DrawioDiagramRenderer.Unsupported {
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        for (String pair : fragment.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                parameters.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        String name = parameters.get(ConfluencePageView.DRAWIO_FRAGMENT);
        String page = parameters.get("page");
        String revision = parameters.get("revision");
        if (name == null || name.isEmpty() || page == null || !page.matches("\\d+")) {
            throw new DrawioDiagramRenderer.Unsupported("Diagrammangaben unvollständig");
        }
        URI uri;
        try {
            uri = URI.create(config.baseUrl() + "/rest/drawio/1.0/diagram/crud/" + encodeUnsafe(name) + "/" + page
                    + (revision != null && revision.matches("\\d+") ? "?revision=" + revision : ""));
        } catch (IllegalArgumentException e) {
            throw new DrawioDiagramRenderer.Unsupported("Diagrammname nicht als Adresse darstellbar");
        }
        LOG.info("[Confluence] draw.io: rendere Diagramm name=" + decoded(name) + " page=" + page + " revision="
                + (revision == null ? "-" : revision));
        ConfluenceHttpResponse response;
        try {
            response = transport.get(uri, session.headers, config.maxResponseBytes());
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                    "draw.io-Diagramm: Confluence nicht erreichbar (" + cause(e) + ")", e);
        }
        if (!response.isSuccess()) {
            throw statusFailure("draw.io-Diagramm", response.status());
        }
        String xml = null;
        try {
            JsonElement json = JsonParser.parseString(response.bodyAsUtf8());
            JsonElement value = json.isJsonObject() ? json.getAsJsonObject().get("xml") : null;
            xml = value != null && value.isJsonPrimitive() ? value.getAsString() : null;
        } catch (RuntimeException e) {
            xml = null;
        }
        if (xml == null || xml.trim().isEmpty()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "draw.io-Diagramm: Antwort ohne Diagramm (xml)");
        }
        byte[] svg = DrawioDiagramRenderer.toSvg(xml).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        LOG.info("[Confluence] draw.io: SVG erzeugt (" + svg.length + " Bytes)");
        return svg;
    }

    private static String decoded(String encoded) {
        try {
            return java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return encoded;
        }
    }

    /** Die PNG-Vorschau bzw. jedes andere Bild der Lese-Ansicht. */
    private byte[] previewImage(Session session, String path) throws KnowledgeSourceException {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Bild ohne gültigen Pfad");
        }
        URI uri;
        try {
            uri = URI.create(config.baseUrl() + encodeUnsafe(path));
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Bild: ungültiger Pfad");
        }
        final URI image = uri;
        return withWebSession(session, IMAGE_ACCEPT, "Bild", new Attempt() {
            @Override
            public byte[] run(Map<String, String> headers) throws KnowledgeSourceException {
                return fetchImage(image, headers);
            }
        });
    }

    /**
     * Der zentrale Weg aller Dateiabrufe des Lesebereichs (Bild, Anhang, Vorschau; Anzeigen, Speichern und Öffnen
     * benutzen dessen Bytes): mit der Web-Sitzung der Quelle ({@link ConfluenceWebSession}).
     *
     * <ol>
     *   <li>Nach einer Bestätigung zuerst nur mit den Cookies, wie der Browser. Reichen sie nicht (Anmeldeseite,
     *       401/403), weiter wie ohne Bestätigung.</li>
     *   <li>Mit Authorization und den Cookies der Sitzung (gegebenenfalls Sitzungs-Cookie-Neuversuch wie bisher).</li>
     *   <li>Kommt die Einmalcode-Seite eines Zwei-Faktor-Plugins ({@link TwoFactorRequired}), gilt die Sitzung als
     *       gesperrt und der Fehler geht mit der Seite an den Aufrufer; den Code sendet {@link #postOneTimeCode}, danach
     *       lädt der Aufrufer neu.</li>
     * </ol>
     */
    private byte[] withWebSession(Session session, String accept, String what, Attempt attempt)
            throws KnowledgeSourceException {
        try {
            if (web.cookiesSuffice()) {
                String cookies = web.cookieHeader();
                Map<String, String> headers = new LinkedHashMap<String, String>();
                headers.put("Accept", accept);
                if (cookies != null) {
                    headers.put("Cookie", cookies);
                }
                try {
                    return attempt.run(Collections.unmodifiableMap(headers));
                } catch (KnowledgeSourceException e) {
                    if (e instanceof TwoFactorRequired || !needsSession(e)) {
                        throw e;
                    }
                    LOG.info("[Confluence] " + what + ": Sitzungs-Cookies allein reichen nicht ("
                            + e.kind() + "), weiter mit Anmeldung und Cookies");
                    web.cookiesAloneInsufficient();
                }
            }
            return withSessionCookie(session, accept, what, attempt);
        } catch (TwoFactorRequired required) {
            if (web.access() == DownloadAccess.UNLOCKED) {
                LOG.info("[Confluence] " + what + ": Einmalcode-Sitzung abgelaufen, neue Bestätigung nötig");
            }
            web.challenged();
            throw required;
        }
    }

    /** Ob Dateien gerade ohne Einmalcode laden (Zustand der Web-Sitzung). */
    DownloadAccess downloadAccess() {
        return web.access();
    }

    /** Das Formular der Einmalcode-Seite oder {@code null}, wenn die Seite keins hat, das hierher zurückgeht. */
    OneTimeCodeForm oneTimeCodeForm(TwoFactorRequired required) {
        OneTimeCodeForm form = OneTimeCodeForm.parse(required.page(), required.html(), config.baseUrl());
        if (form != null) {
            LOG.info("[Confluence] Einmalcode-Abfrage: formular=" + form.formId() + " ziel="
                    + withoutQuery(form.action().toString()) + " felder=" + form.fieldNames() + " neuSenden="
                    + form.resendAvailable() + " sitzungsCookies=" + web.cookieNames());
        }
        return form;
    }

    /**
     * Sendet das Formular der Einmalcode-Seite über denselben Transport (Proxy, TLS, Client-Zertifikat, User-Agent) mit
     * Authorization und den Cookies der Web-Sitzung; gesetzte Cookies übernimmt die Sitzung, Weiterleitungen folgt der
     * Transport. {@code code == null}: neuen Code anfordern. Liefert {@code null}, wenn der Code angenommen wurde,
     * sonst das Formular der Folgeseite (falscher Code, neuer Code gesendet). Code und Cookie-Werte stehen in keiner
     * Logzeile.
     */
    OneTimeCodeForm postOneTimeCode(Session session, OneTimeCodeForm form, char[] code)
            throws KnowledgeSourceException {
        boolean resend = code == null;
        if (resend && !form.resendAvailable()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Diese Einmalcode-Seite kann keinen neuen Code senden");
        }
        challengeLock.lock();
        try {
            byte[] body = resend ? form.resendBody() : form.submitBody(code);
            ConfluenceHttpResponse response;
            try {
                response = transport.postForm(form.action(),
                        withWebCookies(session.headers("text/html,application/xhtml+xml,*/*;q=0.8")), body,
                        config.maxResponseBytes());
            } catch (IOException e) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Einmalcode nicht gesendet (" + cause(e) + ")",
                        e);
            } finally {
                java.util.Arrays.fill(body, (byte) 0);
            }
            web.absorb(response.cookies());
            URI at = response.uri() != null ? response.uri() : form.action();
            OneTimeCodeForm again = baseMediaType(response.contentType()).contains("html")
                    ? OneTimeCodeForm.parse(at, response.body(), config.baseUrl()) : null;
            if (resend) {
                LOG.info("[Confluence] Einmalcode: neuer Code angefordert, status=" + response.status());
                if (again == null && !response.isSuccess()) {
                    throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "Neuer Einmalcode nicht angefordert: "
                            + "Confluence antwortet mit HTTP " + response.status());
                }
                return again != null ? again : form.withError("");
            }
            if (again != null) {
                LOG.info("[Confluence] Einmalcode nicht angenommen: status=" + response.status());
                return again.error().isEmpty() ? again.withError("Der Code wurde nicht angenommen.") : again;
            }
            if (!response.isSuccess()) {
                throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "Einmalcode gesendet, Confluence antwortet "
                        + "mit HTTP " + response.status());
            }
            web.unlocked();
            LOG.info("[Confluence] Einmalcode bestätigt: status=" + response.status() + " sitzungsCookies="
                    + web.cookieNames());
            return null;
        } finally {
            challengeLock.unlock();
        }
    }

    /** Die Header mit den Cookies der Web-Sitzung. */
    private Map<String, String> withWebCookies(Map<String, String> headers) {
        String cookies = web.cookieHeader();
        if (cookies == null) {
            return headers;
        }
        Map<String, String> copy = new LinkedHashMap<String, String>(headers);
        copy.put("Cookie", cookies);
        return Collections.unmodifiableMap(copy);
    }

    /** Die Einmalcode-Seite eines Zwei-Faktor-Plugins statt der Datei; trägt die unveränderte Seite für das Formular. */
    static final class TwoFactorRequired extends KnowledgeSourceException {

        private static final long serialVersionUID = 1L;

        private final transient URI page;
        private final transient byte[] html;

        TwoFactorRequired(KnowledgeSourceException error, URI page, byte[] html) {
            this(error.kind(), error.getMessage(), error.getCause(), page, html);
        }

        private TwoFactorRequired(Kind kind, String message, Throwable cause, URI page, byte[] html) {
            super(kind, message, cause);
            this.page = page;
            this.html = html;
        }

        /** The same challenge (page and form) with additional diagnosis appended to the message. */
        TwoFactorRequired withDetail(String detail) {
            return new TwoFactorRequired(kind(), getMessage() + detail, this, page, html);
        }

        URI page() {
            return page;
        }

        byte[] html() {
            return html;
        }
    }

    /** Ein Abruf mit den gegebenen Kopfzeilen; für {@link #withSessionCookie}. */
    private interface Attempt {
        byte[] run(Map<String, String> headers) throws KnowledgeSourceException;
    }

    /**
     * Führt den Abruf angemeldet aus und wiederholt ihn einmal mit Sitzungs-Cookie, wenn die Anmeldung nicht griff
     * (Bild, Anhang, Vorschau).
     */
    private byte[] withSessionCookie(Session session, String accept, String what, Attempt attempt)
            throws KnowledgeSourceException {
        try {
            return attempt.run(withWebCookies(session.headers(accept)));
        } catch (KnowledgeSourceException first) {
            if (first instanceof TwoFactorRequired || !needsSession(first)) {
                // Eine Einmalcode-Seite löst kein Cookie aus /rest/api/user/current: Die Identität ist bekannt, es
                // fehlt die zweite Stufe (echter Test 0.20.2).
                throw first;
            }
            // Weder MainframeMate noch dieser Client hielten eine Sitzung: Jeder Bildabruf kam nur mit dem
            // Authorization-Header. Instanzen, die Basic nur unter /rest/ annehmen (SSO), leiten /download/ ohne
            // Sitzung auf die Anmeldung um. Darum einmal das Sitzungs-Cookie eines angemeldeten REST-Aufrufs holen
            // und das Bild damit erneut laden; das Cookie lebt nur in diesem Aufruf und wird nie geloggt.
            String[] missing = new String[1];
            String cookie = sessionCookie(session, what, missing);
            if (cookie == null) {
                throw new KnowledgeSourceException(first.kind(), first.getMessage() + "; " + missing[0], first);
            }
            Map<String, String> headers = new LinkedHashMap<String, String>(session.headers(accept));
            headers.put("Cookie", cookie);
            try {
                return attempt.run(Collections.unmodifiableMap(headers));
            } catch (TwoFactorRequired required) {
                // Keep the challenge type through the retry, the cookie session reached the second factor.
                throw required.withDetail(" (auch mit Sitzungs-Cookie aus der REST-Anmeldung)");
            } catch (KnowledgeSourceException second) {
                throw new KnowledgeSourceException(second.kind(), second.getMessage()
                        + " (auch mit Sitzungs-Cookie aus der REST-Anmeldung)", second);
            }
        }
    }

    /**
     * Kodiert, was in einer URI nicht roh stehen darf (Leerzeichen, Umlaute, Anführungszeichen …), als UTF-8-Prozent-
     * kodierung; erlaubte Zeichen und vorhandene {@code %XX} bleiben. Confluence-Anhänge heißen oft mit Leerzeichen
     * (draw.io-Vorschauen „Name des Diagramms.png“); roh scheiterte {@link URI#create} („ungültiger Pfad“), und
     * MainframeMate schickte das Leerzeichen unkodiert.
     */
    static String encodeUnsafe(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "-._~:/?#[]@!$&'()*+,;=%".indexOf(c) >= 0)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** Weiterleitung, 401/403 oder HTML statt Datei: Anmeldung hat beim Download nicht gegriffen. */
    private static boolean needsSession(KnowledgeSourceException e) {
        return e.kind() == Kind.ACCESS_DENIED
                || e.kind() == Kind.INVALID_RESPONSE && e.getMessage() != null && e.getMessage().contains("html");
    }

    /**
     * Cookie-Header aus einem angemeldeten, leichten REST-Aufruf ({@code /rest/api/user/current}) oder {@code null},
     * wenn Confluence keins setzt oder der Aufruf scheitert; dann steht der Grund in {@code missing[0]}.
     */
    private String sessionCookie(Session session, String what, String[] missing) {
        URI uri = URI.create(config.baseUrl() + "/rest/api/user/current");
        ConfluenceHttpResponse response;
        try {
            response = transport.get(uri, session.headers, config.maxResponseBytes());
        } catch (IOException e) {
            LOG.warning("[Confluence] " + what + ": REST-Anmeldung für Sitzungs-Cookie fehlgeschlagen (" + cause(e) + ")");
            missing[0] = "REST-Anmeldung für das Sitzungs-Cookie nicht erreichbar (" + cause(e) + ")";
            return null;
        }
        String[] user = currentUser(response);
        LOG.info("[Confluence] " + what + ": REST-Anmeldung für Sitzungs-Cookie: status=" + response.status() + " cookies="
                + response.cookies().size() + " cookieNamen=" + response.cookieNames() + " benutzerTyp=" + user[0]
                + " benutzer=" + user[1] + " (laut /rest/api/user/current)");
        if (!response.isSuccess()) {
            missing[0] = "REST-Anmeldung für das Sitzungs-Cookie gescheitert (HTTP " + response.status() + ")";
            return null;
        }
        if ("anonymous".equalsIgnoreCase(user[0])) {
            // Ein Cookie einer anonymen Sitzung ist keine Anmeldung; damit zu wiederholen hilft nicht.
            missing[0] = "die REST-Anmeldung meldet einen anonymen Benutzer, ihr Cookie ist keine angemeldete Sitzung";
            return null;
        }
        if (response.cookies().isEmpty()) {
            missing[0] = "Confluence setzt beim angemeldeten REST-Aufruf kein Sitzungs-Cookie";
            return null;
        }
        StringBuilder header = new StringBuilder();
        for (String cookie : response.cookies()) {
            header.append(header.length() == 0 ? "" : "; ").append(cookie);
        }
        return header.toString();
    }

    /**
     * Typ ({@code known}/{@code anonymous}) und Benutzername aus der Antwort von {@code /rest/api/user/current}; „-“,
     * wo die Antwort nichts sagt. Der Name steht nur im lokalen Protokoll.
     */
    static String[] currentUser(ConfluenceHttpResponse response) {
        String[] user = {"-", "-"};
        if (!response.contentType().toLowerCase(Locale.ROOT).contains("json")) {
            user[0] = "(keine JSON-Antwort: " + baseMediaType(response.contentType()) + ")";
            return user;
        }
        try {
            JsonElement json = JsonParser.parseString(response.bodyAsUtf8());
            if (json.isJsonObject()) {
                String type = string(json.getAsJsonObject(), "type");
                String name = string(json.getAsJsonObject(), "username");
                user[0] = type == null ? "-" : type;
                user[1] = name == null ? "-" : name;
            }
        } catch (RuntimeException e) {
            user[0] = "(JSON nicht lesbar)";
        }
        return user;
    }

    /**
     * Untersucht eine HTML-Antwort, die statt einer Datei kam ({@link HtmlResponseDiagnosis}): eine WARNING-Zeile
     * mit Status, Content-Type, Länge, Adresse ohne Query, Weiterleitungen, Titel, Überschrift, Formularen, Zielhosts
     * und den Namen (nie Werten) der gesendeten und gesetzten Cookies; dazu einmal je Quelle und Programmlauf (bis
     * {@link #MAX_DIAGNOSTIC_FILES}) die Seite als {@code confluence-failed-download-<Zeit>.html} im
     * Diagnoseverzeichnis, ohne Werte von Eingabefeldern. Gibt einen kurzen Zusatz für die Fehlermeldung zurück.
     */
    private HtmlResponseDiagnosis diagnoseHtml(String what, URI requested, ConfluenceHttpResponse response,
                                               Map<String, String> sent) {
        URI uri = response.uri() != null ? response.uri() : requested;
        HtmlResponseDiagnosis page;
        try {
            page = HtmlResponseDiagnosis.of(uri, response.body(), response.redirects());
        } catch (RuntimeException e) {
            LOG.warning("[Confluence] " + what + ": HTML-Antwort nicht auswertbar (" + cause(e) + ")");
            return null;
        }
        String file = writeDiagnosticFile(what, uri, response, sent);
        LOG.warning("[Confluence] " + what + ": HTML statt Datei untersucht: status=" + response.status()
                + " contentType=" + response.contentType() + " bytes=" + response.length()
                + " adresse=" + withoutQuery(uri.toString())
                + " weiterleitungen=" + withoutQueries(response.redirects())
                + " gesendet=" + sentDescription(sent)
                + " gesetzteCookies=" + response.cookieNames()
                + " " + page.summary()
                + (file == null ? "" : " diagnosedatei=" + file));
        return page;
    }

    /** Zusatz für die Fehlermeldung: Art und Titel der Seite. */
    private static String pageSuffix(HtmlResponseDiagnosis page) {
        return page == null ? "" : " [Seite: " + page.kind()
                + (page.title().isEmpty() ? "" : " „" + page.title() + "“") + "]";
    }

    /** Bei einer Einmalcode-Seite trägt der Fehler die Seite für die Abfrage ({@link TwoFactorRequired}). */
    private static KnowledgeSourceException htmlFailure(URI at, ConfluenceHttpResponse response,
                                                        HtmlResponseDiagnosis page, KnowledgeSourceException error) {
        if (page != null && page.kind() == HtmlResponseDiagnosis.Kind.TWO_FACTOR) {
            return new TwoFactorRequired(error, at, response.body());
        }
        return error;
    }

    /** Die Adresse, von der eine Datei-Antwort kam: laut Transport, sonst Basis-URL und Pfad. */
    private URI fileUri(String path, ConfluenceHttpResponse response) {
        if (response.uri() != null) {
            return response.uri();
        }
        try {
            return URI.create(config.baseUrl() + encodeUnsafe(path));
        } catch (IllegalArgumentException e) {
            return config.baseUrl();
        }
    }

    /** Authorization-Verfahren und Cookie-Namen eines Requests, nie Werte. */
    static String sentDescription(Map<String, String> sent) {
        String authorization = sent.get("Authorization");
        String scheme = authorization == null ? "-" : authorization.trim().split("\\s+")[0];
        List<String> names = new ArrayList<String>();
        String cookie = sent.get("Cookie");
        if (cookie != null) {
            for (String pair : cookie.split(";")) {
                int eq = pair.indexOf('=');
                names.add((eq < 0 ? pair : pair.substring(0, eq)).trim());
            }
        }
        return "{authorization=" + scheme + ", cookies=" + names + ", accept=" + sent.get("Accept") + "}";
    }

    private static List<String> withoutQueries(List<String> locations) {
        List<String> out = new ArrayList<String>();
        for (String location : locations) {
            out.add(withoutQuery(location));
        }
        return out;
    }

    private String writeDiagnosticFile(String what, URI uri, ConfluenceHttpResponse response,
                                       Map<String, String> sent) {
        java.nio.file.Path directory = config.diagnosticsDirectory();
        if (directory == null || diagnosticFiles.incrementAndGet() > MAX_DIAGNOSTIC_FILES) {
            return null;
        }
        String stamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern(
                "yyyyMMdd-HHmmss-SSS", Locale.ROOT));
        java.nio.file.Path file = directory.resolve("confluence-failed-download-" + stamp + ".html");
        String details = "enterprise-ai-client: HTML-Antwort statt Datei (" + what + ")\n"
                + "     status=" + response.status() + " contentType=" + response.contentType() + " bytes="
                + response.length() + "\n"
                + "     adresse=" + withoutQuery(uri.toString()) + "\n"
                + "     weiterleitungen=" + withoutQueries(response.redirects()) + "\n"
                + "     gesendet=" + sentDescription(sent) + "\n"
                + "     gesetzteCookies=" + response.cookieNames() + "\n"
                + "     Werte von Eingabefeldern und Token-Metafeldern sind durch *** ersetzt; Header und Cookie-Werte"
                + " stehen nicht in der Datei.";
        try {
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.write(file, ("<!-- " + details.replace("--", "- -") + " -->\n"
                    + HtmlResponseDiagnosis.redacted(uri, response.body()))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return file.toString();
        } catch (IOException | RuntimeException e) {
            LOG.warning("[Confluence] Diagnosedatei nicht geschrieben (" + cause(e) + ")");
            return null;
        }
    }

    private byte[] fetchImage(URI requested, Map<String, String> initial) throws KnowledgeSourceException {
        URI uri = requested;
        Map<String, String> headers = initial;
        int max = config.maxAttachmentBytes();
        ConfluenceHttpResponse response;
        for (int hop = 0; ; hop++) {
            try {
                // nach einer Weiterleitung auf einen anderen Host: ohne Anmeldung, Cookie und Client-Zertifikat
                response = hop == 0 ? transport.get(uri, headers, max) : transport.getAnonymous(uri, headers, max);
            } catch (ConfluenceResponseTooLargeException e) {
                throw imageFailure(uri, null, new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                        "Bild größer als " + max + " Bytes (Grenze maxAttachmentBytes)"));
            } catch (IOException e) {
                throw imageFailure(uri, null, new KnowledgeSourceException(Kind.UNAVAILABLE,
                        "Bild: Confluence nicht erreichbar (" + cause(e) + ")", e));
            }
            if (hop == 0) {
                web.absorb(response.cookies());
            }
            URI next = hop < 5 ? foreignRedirect(uri, response) : null;
            if (next == null) {
                break;
            }
            // Weiterleitungen auf denselben Host folgt der Transport angemeldet; eine auf einen anderen Host (etwa
            // einen Download-Host) folgt der Bildweg wie ein Browser, aber ohne Authorization und Cookie:
            // Anmeldedaten verlassen die eingestellte Confluence nie.
            LOG.info("[Confluence] Bild: Weiterleitung von " + withoutQuery(uri.toString()) + " auf "
                    + withoutQuery(next.toString()) + ", ohne Anmeldung gefolgt");
            uri = next;
            headers = withoutAuthorization(headers);
        }
        if (!response.isSuccess()) {
            throw imageFailure(uri, response, statusFailure("Bild", response.status()));
        }
        byte[] body = response.body();
        String received = notAnImage(response.contentType(), body);
        if (received != null) {
            HtmlResponseDiagnosis page = received.contains("html") ? diagnoseHtml("Bild", uri, response, headers)
                    : null;
            throw htmlFailure(response.uri() != null ? response.uri() : uri, response, page,
                    imageFailure(uri, response, new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                            "Bild: erwartet Bild, erhalten " + received + " (HTTP " + response.status() + ", "
                                    + body.length + " Bytes)" + pageSuffix(page))));
        }
        return body;
    }

    /** Ziel einer Weiterleitung, die der Transport nicht verfolgt hat (anderer Host); nie von https auf http. */
    private static URI foreignRedirect(URI from, ConfluenceHttpResponse response) {
        int status = response.status();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308
                || response.location() == null) {
            return null;
        }
        URI target;
        try {
            target = from.resolve(response.location());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = target.getScheme();
        if (target.getHost() == null || target.getRawUserInfo() != null
                || !"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        return target;
    }

    private static Map<String, String> withoutAuthorization(Map<String, String> headers) {
        Map<String, String> copy = new LinkedHashMap<String, String>(headers);
        copy.remove("Authorization");
        copy.remove("Cookie");
        return Collections.unmodifiableMap(copy);
    }

    /** {@code null}, wenn die Antwort ein Bild ist; sonst, was stattdessen kam. */
    static String notAnImage(String contentType, byte[] body) {
        String media = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int semicolon = media.indexOf(';');
        media = (semicolon < 0 ? media : media.substring(0, semicolon)).trim();
        if (media.startsWith("image/") || looksLikeImage(body)) {
            return null;
        }
        if (media.isEmpty()) {
            return "eine Antwort ohne Content-Type";
        }
        return media.contains("html") ? media + " (vermutlich eine Anmeldeseite)" : media;
    }

    private static boolean looksLikeImage(byte[] data) {
        if (data.length > 3 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return true;
        }
        if (data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return true;
        }
        if (data.length > 3 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return true;
        }
        if (data.length > 1 && data[0] == 'B' && data[1] == 'M') {
            return true;
        }
        if (data.length > 11 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return true;
        }
        return isSvgDocument(data);
    }

    /**
     * SVG nur, wenn {@code <svg} das Wurzelelement ist: vor ihm stehen höchstens BOM, Leerraum, XML-Deklaration,
     * Kommentare und DOCTYPE. Eine HTML-Seite mit eingebettetem SVG ist kein Bild.
     */
    static boolean isSvgDocument(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 4096), java.nio.charset.StandardCharsets.UTF_8);
        int i = head.startsWith("\uFEFF") ? 1 : 0;
        while (true) {
            while (i < head.length() && Character.isWhitespace(head.charAt(i))) {
                i++;
            }
            String close = head.startsWith("<?", i) ? "?>" : head.startsWith("<!--", i) ? "-->"
                    : head.regionMatches(true, i, "<!DOCTYPE", 0, 9) ? ">" : null;
            if (close == null) {
                break;
            }
            int end = head.indexOf(close, i);
            if (end < 0) {
                return false;
            }
            i = end + close.length();
        }
        return head.startsWith("<svg", i)
                && (head.length() == i + 4 || !Character.isLetterOrDigit(head.charAt(i + 4)));
    }

    /** Schreibt die Logzeile zum fehlgeschlagenen Bild (ohne Header) und gibt den Fehler zurück. */
    private static KnowledgeSourceException imageFailure(URI uri, ConfluenceHttpResponse response,
                                                         KnowledgeSourceException error) {
        StringBuilder line = new StringBuilder("[Confluence] Bild nicht geladen: url=")
                .append(withoutQuery(uri.toString()))
                .append(" status=").append(response == null ? "-" : String.valueOf(response.status()))
                .append(" contentType=").append(response == null || response.contentType().isEmpty() ? "-"
                        : response.contentType())
                .append(" bytes=").append(response == null ? "-" : String.valueOf(response.length()));
        if (response != null && response.location() != null) {
            line.append(" weiterleitung=").append(withoutQuery(response.location()));
        }
        line.append(" fehler=").append(error.kind()).append(": ").append(error.getMessage());
        LOG.warning(line.toString());
        return error;
    }

    /** Adresse ohne Query (Logzeilen). */
    static String withoutQuery(String location) {
        int query = location.indexOf('?');
        int fragment = location.indexOf('#');
        int end = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
        return end < 0 ? location : location.substring(0, end);
    }

    /** Expansion of the search endpoint: the hit's content with version and space (revision, partition). */
    private static final String SEARCH_EXPAND = "content.version,content.space";

    /** Maximum length of a normalised search excerpt. */
    static final int EXCERPT_MAX_LENGTH = 300;

    /**
     * Page search over the Confluence search endpoint {@code /rest/api/search} (the one the web search uses), with
     * {@code excerpt=highlight}, which requests real page text per hit without loading the page (a hit without an
     * excerpt stays valid with an empty snippet). Each result is a search
     * wrapper {@code {content: {...}, title, excerpt, url, lastModified}}; results without a page as content (spaces,
     * users, attachments) are skipped. Pages through {@code _links.next} or, when the server sends none, by
     * {@code start} offsets while {@code totalSize} says more hits exist, until {@code limit} pages are collected.
     * The order of the hits is the order of Confluence.
     */
    SearchResults search(Session session, String cql, int limit) throws KnowledgeSourceException {
        String what = "Suche";
        String base = "/rest/api/search?cql=" + query(cql) + "&excerpt=highlight&expand=" + SEARCH_EXPAND
                + "&includeArchivedSpaces=false";
        int pageSize = Math.min(config.pageSize(), limit);
        List<Page> pages = new ArrayList<Page>();
        Set<String> visited = new HashSet<String>();
        int httpResults = 0;
        long totalSize = -1;
        int start = 0;
        String next = base + "&start=0&limit=" + pageSize;
        while (pages.size() < limit && next != null) {
            if (!visited.add(next)) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": Folgelink wiederholt sich");
            }
            JsonObject json = getJson(session, next, what);
            List<JsonObject> results = results(json, what);
            httpResults += results.size();
            for (JsonObject result : results) {
                Page page = searchHit(result);
                if (page != null) {
                    pages.add(page);
                    if (pages.size() >= limit) {
                        break;
                    }
                }
            }
            long reportedTotal = number(json, "totalSize");
            if (reportedTotal >= 0) {
                totalSize = reportedTotal;
            }
            start += results.size();
            next = nextLink(json, what);
            if (next == null && !results.isEmpty() && totalSize > start) {
                next = base + "&start=" + start + "&limit=" + pageSize;
            }
        }
        LOG.info("[Confluence] Suche: HTTP-Ergebnisse=" + httpResults + ", erkannte Seiten=" + pages.size()
                + ", totalSize=" + totalSize);
        return new SearchResults(pages, totalSize);
    }

    /** One result of {@code /rest/api/search} as page, or {@code null} if it is no page. */
    private Page searchHit(JsonObject result) throws KnowledgeSourceException {
        JsonObject content = object(result, "content");
        if (content == null || !"page".equals(string(content, "type"))) {
            return null;
        }
        Page page = toPage(content);
        if (page.title.isEmpty()) {
            page = page.withTitle(excerpt(string(result, "title")));
        }
        return page.withExcerpt(excerpt(string(result, "excerpt")))
                .withSearchFallbacks(string(result, "url"), instant(string(result, "lastModified")));
    }

    /**
     * Plain text of a search excerpt: highlight markers and HTML markup removed, HTML entities decoded, whitespace
     * collapsed, cut to {@link #EXCERPT_MAX_LENGTH} characters; empty for {@code null}.
     */
    static String excerpt(String raw) {
        if (raw == null) {
            return "";
        }
        // Block-level tags separate words, inline tags (highlight spans, bold) do not.
        String text = raw.replace("@@@hl@@@", "").replace("@@@endhl@@@", "")
                .replaceAll("(?i)<(br|/?p|/?div|/?li|/?tr|/?td|/?th|/?h[1-6])\\b[^>]*>", " ")
                .replaceAll("<[^>]*>", "");
        text = decodeEntities(text).replaceAll("[\\s\\u00A0]+", " ").trim();
        return text.length() > EXCERPT_MAX_LENGTH ? text.substring(0, EXCERPT_MAX_LENGTH).trim() + "…" : text;
    }

    /** Decodes all HTML entities (named and numeric) with Jsoup's entity table. */
    static String decodeEntities(String text) {
        return org.jsoup.parser.Parser.unescapeEntities(text, false);
    }

    private static long number(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        return value.getAsLong();
    }

    /**
     * Pages of one search in Confluence order plus Confluence's {@code totalSize} ({@code -1} if not reported).
     * {@code totalSize} counts potential hits of the query, not pages whose content was evaluated; it stays inside
     * the adapter until the source search contract gains a neutral total (follow-up of issue #150).
     */
    static final class SearchResults {
        final List<Page> pages;
        final long totalSize;

        SearchResults(List<Page> pages, long totalSize) {
            this.pages = Collections.unmodifiableList(pages);
            this.totalSize = totalSize;
        }
    }

    // --- Navigation (issue #97) ---------------------------------------------------------------------------

    /** Expansion for tree entries: title and whether child pages exist (Confluence {@code childTypes}). */
    private static final String TREE_EXPAND = "childTypes.page";

    /**
     * Load a page with its space and its full ancestor chain (ids and titles, top down) like MainframeMate's
     * {@code getContentWithAncestorsJson}.
     *
     * @throws KnowledgeSourceException {@code NOT_FOUND} if the content does not exist or is no page
     */
    Lineage lineage(Session session, String contentId) throws KnowledgeSourceException {
        JsonObject json = getJson(session, "/rest/api/content/" + contentId + "?expand=space,ancestors,"
                + TREE_EXPAND, "Seitenhierarchie");
        if (!"page".equals(string(json, "type"))) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Inhalt " + contentId + " ist keine Seite");
        }
        TreePage page = toTreePage(json);
        JsonObject space = object(json, "space");
        String spaceKey = space == null ? null : string(space, "key");
        if (spaceKey == null || spaceKey.trim().isEmpty()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Seitenhierarchie: Seite ohne Space");
        }
        List<TreePage> ancestors = new ArrayList<TreePage>();
        JsonArray chain = array(json, "ancestors");
        for (JsonElement ancestor : chain == null ? new JsonArray() : chain) {
            if (ancestor.isJsonObject() && ConfluenceIds.isContentId(string(ancestor.getAsJsonObject(), "id"))) {
                ancestors.add(new TreePage(string(ancestor.getAsJsonObject(), "id"),
                        nullToEmpty(string(ancestor.getAsJsonObject(), "title")), Boolean.TRUE));
            }
        }
        return new Lineage(spaceKey.trim(), nullToEmpty(string(space, "name")), ancestors, page);
    }

    /** The first request of the top-level pages of a space ({@code depth=root}). */
    String rootPagesPath(String spaceKey) {
        return "/rest/api/space/" + pathSegment(spaceKey) + "/content/page?depth=root&expand=" + TREE_EXPAND
                + "&start=0&limit=" + config.pageSize();
    }

    /** The first request of the direct child pages of a page. */
    String childPagesPath(String contentId) {
        return "/rest/api/content/" + contentId + "/child/page?expand=" + TREE_EXPAND + "&start=0&limit="
                + config.pageSize();
    }

    /**
     * Load exactly one batch of a page list ({@link #rootPagesPath}, {@link #childPagesPath} or the {@code next} link of
     * an earlier batch); {@link TreeBatch#next} is the next link or {@code null}.
     */
    TreeBatch treeBatch(Session session, String path) throws KnowledgeSourceException {
        if (path == null || !path.startsWith("/rest/api/") || path.contains("//")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Seitenbaum: ungültiger Folgelink");
        }
        JsonObject json = getJson(session, path, "Seitenbaum");
        List<TreePage> pages = new ArrayList<TreePage>();
        for (JsonObject result : results(json, "Seitenbaum")) {
            String type = string(result, "type");
            if ((type == null || "page".equals(type)) && ConfluenceIds.isContentId(string(result, "id"))) {
                pages.add(toTreePage(result));
            }
        }
        String next = nextLink(json, "Seitenbaum");
        if (next != null && next.equals(path)) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Seitenbaum: Folgelink wiederholt sich");
        }
        return new TreeBatch(pages, next);
    }

    /**
     * The titles of those pages among {@code ids} that exist and are visible, via CQL {@code id in (...)} in batches
     * of the page size; pages missing from the result are deleted or restricted.
     */
    Map<String, String> visiblePageTitles(Session session, List<String> ids) throws KnowledgeSourceException {
        Map<String, String> titles = new LinkedHashMap<String, String>();
        for (int from = 0; from < ids.size(); from += config.pageSize()) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + config.pageSize()));
            StringBuilder cql = new StringBuilder("type=page AND id in (");
            for (int i = 0; i < chunk.size(); i++) {
                cql.append(i == 0 ? "" : ",").append(chunk.get(i));
            }
            cql.append(')');
            for (Page page : paged(session, "/rest/api/content/search?cql=" + query(cql.toString()),
                    "Verweisziele", chunk.size(), new Item<Page>() {
                        @Override
                        public Page map(JsonObject json) throws KnowledgeSourceException {
                            JsonObject content = object(json, "content");
                            return toPage(content == null ? json : content);
                        }
                    })) {
                titles.put(page.id, page.title);
            }
        }
        return titles;
    }

    private static TreePage toTreePage(JsonObject json) {
        JsonObject childTypes = object(json, "childTypes");
        JsonObject page = object(childTypes, "page");
        Boolean hasChildren = null;
        if (page != null && page.get("value") != null && page.get("value").isJsonPrimitive()) {
            hasChildren = page.get("value").getAsBoolean();
        }
        return new TreePage(string(json, "id"), nullToEmpty(string(json, "title")), hasChildren);
    }

    /** A page in the tree: id, title and whether it has child pages ({@code null}: unknown). */
    static final class TreePage {
        final String id;
        final String title;
        final Boolean hasChildren;

        TreePage(String id, String title, Boolean hasChildren) {
            this.id = id;
            this.title = title;
            this.hasChildren = hasChildren;
        }
    }

    /** One batch of a page list and the link of the next batch ({@code null}: last batch). */
    static final class TreeBatch {
        final List<TreePage> pages;
        final String next;

        TreeBatch(List<TreePage> pages, String next) {
            this.pages = Collections.unmodifiableList(pages);
            this.next = next;
        }
    }

    /** A page with its space and ancestors (top down). */
    static final class Lineage {
        final String spaceKey;
        final String spaceName;
        final List<TreePage> ancestors;
        final TreePage page;

        Lineage(String spaceKey, String spaceName, List<TreePage> ancestors, TreePage page) {
            this.spaceKey = spaceKey;
            this.spaceName = spaceName;
            this.ancestors = Collections.unmodifiableList(ancestors);
            this.page = page;
        }
    }

    // --- HTTP ---------------------------------------------------------------------------------------------

    /** Wandelt ein Listenelement um; {@code null} heißt: zählt nicht und wird übersprungen. */
    private interface Item<T> {
        T map(JsonObject json) throws KnowledgeSourceException;
    }

    /**
     * Blättert über einen Listen-Endpunkt, bis {@code max} verwendbare Elemente beisammen sind oder Confluence
     * keinen {@code _links.next} mehr liefert. Nur der erste Aufruf setzt {@code start}/{@code limit}; danach
     * wird der gelieferte relative Folgelink unverändert verfolgt (Data Center: relativ zum Kontextpfad, also
     * unter {@code /rest/api/}). Ein Link außerhalb davon oder ein sich wiederholender Link ist
     * {@code INVALID_RESPONSE}.
     */
    private <T> List<T> paged(Session session, String path, String what, int max, Item<T> item)
            throws KnowledgeSourceException {
        List<T> all = new ArrayList<T>();
        Set<String> visited = new HashSet<String>();
        String next = path + "&start=0&limit=" + Math.min(config.pageSize(), max);
        while (all.size() < max && next != null) {
            if (!visited.add(next)) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": Folgelink wiederholt sich");
            }
            JsonObject json = getJson(session, next, what);
            for (JsonObject result : results(json, what)) {
                T mapped = item.map(result);
                if (mapped != null) {
                    all.add(mapped);
                    if (all.size() >= max) {
                        break;
                    }
                }
            }
            next = nextLink(json, what);
        }
        return all;
    }

    private static String nextLink(JsonObject json, String what) throws KnowledgeSourceException {
        String next = string(object(json, "_links"), "next");
        if (next == null) {
            return null;
        }
        if (!next.startsWith("/rest/api/")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": Folgelink außerhalb der REST-API");
        }
        return next;
    }

    private JsonObject getJson(Session session, String path, String what) throws KnowledgeSourceException {
        ConfluenceHttpResponse response = send(session, path, what, config.maxResponseBytes());
        if (!response.contentType().toLowerCase(java.util.Locale.ROOT).contains("json")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                    what + ": keine JSON-Antwort (Anmeldeseite eines Proxys?)");
        }
        try {
            JsonElement element = JsonParser.parseString(response.bodyAsUtf8());
            if (!element.isJsonObject()) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": JSON-Objekt erwartet");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": JSON nicht lesbar");
        }
    }

    private ConfluenceHttpResponse send(Session session, String path, String what, int maxBytes)
            throws KnowledgeSourceException {
        URI uri;
        try {
            uri = URI.create(config.baseUrl() + path);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": ungültiger Pfad");
        }
        ConfluenceHttpResponse response;
        try {
            response = transport.get(uri, session.headers, maxBytes);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, what + ": Confluence nicht erreichbar ("
                    + cause(e) + ")", e);
        }
        if (response.isSuccess()) {
            return response;
        }
        throw statusFailure(what, response.status());
    }

    /** Bildet einen Fehlerstatus auf {@link Kind} ab (siehe Klassenkommentar). */
    private static KnowledgeSourceException statusFailure(String what, int status) {
        if (status == 401 || status == 403) {
            return new KnowledgeSourceException(Kind.ACCESS_DENIED, what + ": Zugriff verweigert (HTTP " + status + ")");
        }
        if (status >= 300 && status < 400) {
            return new KnowledgeSourceException(Kind.ACCESS_DENIED,
                    what + ": Weiterleitung (HTTP " + status + "), vermutlich nicht angemeldet");
        }
        if (status == 404) {
            return new KnowledgeSourceException(Kind.NOT_FOUND, what + ": nicht gefunden (HTTP 404)");
        }
        if (status == 429 || status >= 500) {
            return new KnowledgeSourceException(Kind.UNAVAILABLE, what + ": Confluence antwortet mit HTTP " + status);
        }
        return new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": unerwartete Antwort HTTP " + status);
    }

    /** Die tiefste Ursache in einer Zeile, z. B. {@code SSLHandshakeException: Received fatal alert: bad_certificate}. */
    static String cause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.trim().isEmpty() ? ""
                : ": " + message.trim().replace('\n', ' '));
    }

    // --- JSON-Abbildung -----------------------------------------------------------------------------------

    private Page toPage(JsonObject json) throws KnowledgeSourceException {
        return toPage(json, false);
    }

    private Page toPage(JsonObject json, boolean exportView) throws KnowledgeSourceException {
        String id = string(json, "id");
        if (!ConfluenceIds.isContentId(id)) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Seite ohne gültige ID");
        }
        JsonObject space = object(json, "space");
        JsonObject version = object(json, "version");
        JsonObject bodies = object(json, "body");
        JsonObject body = exportView ? object(bodies, "export_view") : null;
        if (body == null || string(body, "value") == null || string(body, "value").trim().isEmpty()) {
            body = object(bodies, "view");
        }
        List<String> ancestors = new ArrayList<String>();
        JsonArray ancestorArray = array(json, "ancestors");
        if (ancestorArray != null) {
            for (JsonElement ancestor : ancestorArray) {
                if (ancestor.isJsonObject() && string(ancestor.getAsJsonObject(), "id") != null) {
                    ancestors.add(string(ancestor.getAsJsonObject(), "id"));
                }
            }
        }
        List<String> labels = new ArrayList<String>();
        JsonObject labelPage = object(object(json, "metadata"), "labels");
        JsonArray labelArray = array(labelPage, "results"); // optional, fehlt ohne expand=metadata.labels
        for (JsonElement label : labelArray == null ? new JsonArray() : labelArray) {
            if (label.isJsonObject() && string(label.getAsJsonObject(), "name") != null) {
                labels.add(string(label.getAsJsonObject(), "name"));
            }
        }
        JsonObject links = object(json, "_links");
        return new Page(id, nullToEmpty(string(json, "title")),
                space == null ? null : string(space, "key"),
                version == null ? null : string(version, "number"), version == null ? null : instant(string(version, "when")),
                ancestors.isEmpty() ? null : ancestors.get(ancestors.size() - 1),
                body == null ? null : string(body, "value"),
                links == null ? null : string(links, "webui"), labels)
                .withServerBase(links == null ? null : string(links, "base"));
    }

    private static Attachment toAttachment(JsonObject json, String containerId) throws KnowledgeSourceException {
        String id = attachmentId(json);
        if (id == null) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Anhang ohne gültige ID (" + idShape(json) + ")");
        }
        JsonObject version = object(json, "version");
        JsonObject metadata = object(json, "metadata");
        JsonObject extensions = object(json, "extensions");
        JsonObject links = object(json, "_links");
        String mediaType = metadata == null ? null : string(metadata, "mediaType");
        if (mediaType == null && extensions != null) {
            mediaType = string(extensions, "mediaType");
        }
        long size = -1;
        if (extensions != null && extensions.has("fileSize")) {
            try {
                size = extensions.get("fileSize").getAsLong();
            } catch (RuntimeException e) {
                size = -1;
            }
        }
        return new Attachment(id, nullToEmpty(string(json, "title")), containerId,
                mediaType == null ? "application/octet-stream" : mediaType, size,
                version == null ? null : string(version, "number"), version == null ? null : instant(string(version, "when")),
                links == null ? null : string(links, "download"), links == null ? null : string(links, "webui"));
    }

    /**
     * Die Anhang-ID im Format {@code att<Ziffern>} oder {@code null}. Confluence nennt Anhänge in der REST-API
     * {@code att123}; eine reine Ziffernfolge (als Text oder JSON-Zahl) ist dieselbe Content-ID ohne Präfix und wird
     * ergänzt.
     */
    static String attachmentId(JsonObject json) {
        String id = string(json, "id");
        if (id == null) {
            return null;
        }
        id = id.trim();
        if (ConfluenceIds.isContentId(id)) {
            id = "att" + id;
        }
        return ConfluenceIds.isAttachmentId(id) ? id : null;
    }

    /** Wie die ID aussah, ohne ihren Wert: für Meldungen und Logzeilen. */
    static String idShape(JsonObject json) {
        JsonElement value = json == null ? null : json.get("id");
        if (value == null || value.isJsonNull()) {
            return "fehlt";
        }
        if (!value.isJsonPrimitive()) {
            return "ist kein Wert (" + (value.isJsonArray() ? "Liste" : "Objekt") + ")";
        }
        String text = value.getAsString();
        if (text.trim().isEmpty()) {
            return "ist leer";
        }
        return "hat unerwartete Form (" + text.length() + " Zeichen, " + (value.getAsJsonPrimitive().isNumber()
                ? "Zahl" : "Text") + ")";
    }

    /** {@code results} eines Listen-Endpunkts; fehlt es, ist die Antwort fehlerhaft, nicht leer. */
    private static List<JsonObject> results(JsonObject json, String what) throws KnowledgeSourceException {
        JsonArray array = array(json, "results");
        if (array == null) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": Liste ohne results");
        }
        List<JsonObject> list = new ArrayList<JsonObject>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                list.add(element.getAsJsonObject());
            }
        }
        return list;
    }

    private static JsonObject object(JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement value = json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Instant instant(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException iso) {
            try {
                return OffsetDateTime.parse(value, COMPACT_OFFSET).toInstant();
            } catch (DateTimeParseException compact) {
                return null;
            }
        }
    }

    private static String query(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pathSegment(String value) {
        return query(value);
    }

    // --- Werte --------------------------------------------------------------------------------------------

    /**
     * Header eines Vorgangs (Accept und ggf. Authorization). Lebt nur für einen Port-Aufruf im Stack des
     * Aufrufers und wird nie in Feldern gehalten, geloggt oder ausgegeben.
     */
    static final class Session {

        private final Map<String, String> headers;

        Session(String authorization) {
            Map<String, String> map = new LinkedHashMap<String, String>();
            map.put("Accept", "application/json");
            if (authorization != null) {
                map.put("Authorization", authorization);
            }
            this.headers = Collections.unmodifiableMap(map);
        }

        /** Dieselben Header mit anderem {@code Accept} (Bilder statt JSON). */
        Map<String, String> headers(String accept) {
            Map<String, String> map = new LinkedHashMap<String, String>(headers);
            map.put("Accept", accept);
            return Collections.unmodifiableMap(map);
        }

        @Override
        public String toString() {
            return "Session[***]";
        }
    }

    static final class Space {
        final String key;
        final String name;
        /** Content-ID der Startseite oder {@code null}. */
        final String homepageId;

        Space(String key, String name, String homepageId) {
            this.key = key;
            this.name = name;
            this.homepageId = homepageId;
        }
    }

    static final class Page {
        final String id;
        final String title;
        final String spaceKey;
        final String version;
        final Instant modifiedAt;
        final String parentId;
        final String html;
        final String webUi;
        final List<String> labels;
        /** Plain-text excerpt from the search; empty outside the search. */
        final String excerpt;
        /**
         * {@code _links.base} der Antwort: die Basis-URL, die Confluence selbst für sich eingestellt hat und mit der
         * {@code export_view} absolute Bildadressen schreibt; {@code null}, wenn sie fehlt.
         */
        final String serverBase;
        /** {@code body.storage} der Lese-Ansicht (draw.io-Makroparameter); sonst {@code null}. */
        final String storage;

        Page(String id, String title, String spaceKey, String version, Instant modifiedAt,
             String parentId, String html, String webUi, List<String> labels) {
            this(id, title, spaceKey, version, modifiedAt, parentId, html, webUi, labels, "", null, null);
        }

        private Page(String id, String title, String spaceKey, String version, Instant modifiedAt,
                     String parentId, String html, String webUi, List<String> labels, String excerpt,
                     String serverBase, String storage) {
            this.id = id;
            this.title = title;
            this.spaceKey = spaceKey;
            this.version = version;
            this.modifiedAt = modifiedAt;
            this.parentId = parentId;
            this.html = html;
            this.webUi = webUi;
            this.labels = Collections.unmodifiableList(labels);
            this.excerpt = excerpt;
            this.serverBase = serverBase;
            this.storage = storage;
        }

        Page withExcerpt(String excerpt) {
            return new Page(id, title, spaceKey, version, modifiedAt, parentId, html, webUi, labels, excerpt,
                    serverBase, storage);
        }

        Page withTitle(String newTitle) {
            return new Page(id, newTitle, spaceKey, version, modifiedAt, parentId, html, webUi, labels, excerpt,
                    serverBase, storage);
        }

        /** Fill web address and modification time from the search wrapper where the content lacks them. */
        Page withSearchFallbacks(String url, Instant lastModified) {
            String address = webUi == null || webUi.trim().isEmpty() ? url : webUi;
            Instant when = modifiedAt == null ? lastModified : modifiedAt;
            return new Page(id, title, spaceKey, version, when, parentId, html, address, labels, excerpt,
                    serverBase, storage);
        }

        Page withServerBase(String base) {
            return new Page(id, title, spaceKey, version, modifiedAt, parentId, html, webUi, labels, excerpt, base,
                    storage);
        }

        Page withStorage(String storageValue) {
            return new Page(id, title, spaceKey, version, modifiedAt, parentId, html, webUi, labels, excerpt,
                    serverBase, storageValue);
        }
    }

    static final class Attachment {
        final String id;
        final String title;
        final String containerId;
        final String mediaType;
        final long size;
        final String version;
        final Instant modifiedAt;
        final String downloadPath;
        final String webUi;

        Attachment(String id, String title, String containerId, String mediaType, long size, String version,
                   Instant modifiedAt, String downloadPath, String webUi) {
            this.id = id;
            this.title = title;
            this.containerId = containerId;
            this.mediaType = mediaType;
            this.size = size;
            this.version = version;
            this.modifiedAt = modifiedAt;
            this.downloadPath = downloadPath;
            this.webUi = webUi;
        }
    }
}
