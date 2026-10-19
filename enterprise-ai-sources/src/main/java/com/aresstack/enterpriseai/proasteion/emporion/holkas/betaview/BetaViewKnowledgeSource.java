package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Das BetaView-Dokumentarchiv als {@link KnowledgeSourcePort}.
 *
 * <ul>
 *   <li>Bestand: die Ergebnisliste eines Favoriten ({@code select.action} → {@code result.action} →
 *       {@code showResult.action}) mit Zeitraum und Mustern aus den Einstellungen; jede Zeile wird einmal geöffnet,
 *       um ihre stabile Dokument-ID zu erfahren.</li>
 *   <li>IDs: {@code betaview:<Basis-Locator>/<Favoriten-ID>/<Dokument-ID>}; Revision: die Dokument-ID (BetaView
 *       legt für jede neue Liste ein neues Dokument an).</li>
 *   <li>Inhalt: {@code opendocumentlink.action}, dann der Download im Originalformat
 *       ({@code downloadDialog.action} → {@code downloadWithOptions.action}); Text über {@code deigma}.</li>
 *   <li>Anmeldung: Anmeldedaten aus dem {@link BetaViewCredentialsProvider} nur für den Login; die Sitzung
 *       (Cookies, CSRF-Token) gilt für die Quelle, bis der Server sie ablehnt, dann einmal neu anmelden.</li>
 * </ul>
 * Zugriffe sind serialisiert, weil BetaView das geöffnete Dokument je Sitzung führt.
 */
final class BetaViewKnowledgeSource implements KnowledgeSourcePort {

    static final String SCHEME = "betaview";

    private static final int PREFIX_BYTES = 8192;
    private static final int TITLE_CELLS = 4;

    private final SourceAddress sourceId;
    private final URI base;
    private final String locator;
    private final BetaViewSelection selection;
    private final BetaViewCredentialsProvider credentials;
    private final BetaViewTransport transport;
    private final ContentDetector detector;
    private final ExtractionRegistry extractors;
    private final long maxFileBytes;
    private BetaViewSession session;

    BetaViewKnowledgeSource(SourceAddress sourceId, URI base, BetaViewSelection selection,
                            BetaViewCredentialsProvider credentials, BetaViewTransport transport,
                            ContentDetector detector, ExtractionRegistry extractors, long maxFileBytes) {
        if (sourceId == null || base == null || selection == null || credentials == null || transport == null
                || detector == null || extractors == null) {
            throw new IllegalArgumentException("all arguments are required");
        }
        this.sourceId = sourceId;
        this.base = base;
        this.locator = BetaViewSourceProvider.baseLocator(base.toString());
        this.selection = selection;
        this.credentials = credentials;
        this.transport = transport;
        this.detector = detector;
        this.extractors = extractors;
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public synchronized List<KnowledgeResource> discover(final SourceScope scope) throws KnowledgeSourceException {
        return withSession(new SessionCall<List<KnowledgeResource>>() {
            @Override
            public List<KnowledgeResource> call(BetaViewSession s) throws IOException {
                List<BetaViewHtml.ResultRow> rows = BetaViewHtml.resultRows(results(s));
                List<KnowledgeResource> found = new ArrayList<KnowledgeResource>();
                Set<String> seen = new LinkedHashSet<String>();
                try {
                    for (BetaViewHtml.ResultRow row : rows) {
                        if (found.size() >= scope.maxResources()) {
                            break;
                        }
                        BetaViewHtml.OpenedDocument opened = BetaViewHtml.activeDocument(s.getText(row.openAction()));
                        if (opened == null) {
                            continue;
                        }
                        KnowledgeResource resource = resource(s, opened, title(row));
                        if (seen.add(resource.id().value())) {
                            found.add(resource);
                        }
                    }
                } finally {
                    closeAll(s);
                }
                return found;
            }
        });
    }

    @Override
    public synchronized KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        final String[] ids = idsOf(resourceId);
        return withSession(new SessionCall<KnowledgeDocument>() {
            @Override
            public KnowledgeDocument call(BetaViewSession s) throws IOException {
                try {
                    String html = s.getText(openLink(ids[0], ids[1]));
                    BetaViewHtml.OpenedDocument opened = BetaViewHtml.activeDocument(html);
                    String title = opened != null && !opened.title().isEmpty() ? opened.title() : ids[1];
                    BetaViewTransport.Response download = download(s);
                    return document(resource(s, new BetaViewHtml.OpenedDocument(ids[1], ids[0], title), title),
                            download);
                } finally {
                    closeAll(s);
                }
            }
        });
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        idsOf(resourceId);
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "BetaViewKnowledgeSource{" + sourceId + ", " + locator + "}";
    }

    // ── Ablauf ───────────────────────────────────────────────────────────────────────────────────────────────

    /** Ergebnisliste des Favoriten (Struts-Token aus {@code select.action}). */
    private String results(BetaViewSession s) throws IOException {
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(
                s.getText("select.action?favoriteID=" + selection.favoriteId()));
        String tokenName = hidden.get("struts.token.name");
        if (tokenName == null || hidden.get(tokenName) == null) {
            throw new IOException("select page without struts token");
        }
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("struts.token.name", tokenName);
        form.put(tokenName, hidden.get(tokenName));
        form.putAll(selection.formFields());
        s.postText("result.action", form);
        return s.getText("showResult.action");
    }

    /** Das geöffnete Dokument im Originalformat (aus MainframeMate {@code DownloadDocumentUseCase}, alle Seiten). */
    private BetaViewTransport.Response download(BetaViewSession s) throws IOException {
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(s.postText(
                "downloadDialog.action?&init=yes&source=docbrowser", new LinkedHashMap<String, String>()));
        Map<String, String> form = new LinkedHashMap<String, String>();
        String tokenName = hidden.get("struts.token.name");
        if (tokenName != null) {
            form.put("struts.token.name", tokenName);
            if (hidden.get(tokenName) != null) {
                form.put(tokenName, hidden.get(tokenName));
            }
        }
        String[][] fromDialog = {{"function", "download"}, {"product", "BetaDocZ"}, {"source", "docbrowser"},
                {"pages", "1"}, {"docLink", "false"}, {"convert", "N"}, {"convertEnabled", "false"},
                {"csvEnabled", "true"}, {"doctype", "txt"}, {"jobinfo", "false"}, {"textenco", "UTF-8"},
                {"bss_ukey", ""}, {"idxstatus", ""}, {"hasHiddenRanges", "false"}, {"dbinst", ""}, {"docid", ""},
                {"lineColumnRanges", ""}};
        for (String[] field : fromDialog) {
            String value = hidden.get(field[0]);
            form.put(field[0], value != null ? value : field[1]);
        }
        form.put("markedPages", "");
        form.put("suffix", "");
        form.put("rangePrintStyle", "");
        form.put("printStyle", "printAllPages");
        form.put("rangeBegin", "1");
        form.put("rangeEnd", form.get("pages"));
        form.put("downloadFormat", "ORIG");
        form.put("csvStyle", "csv_int");
        form.put("delimiter", "comma");
        form.put("csv_int", "10");
        form.put("csv_char", "");
        form.put("csv_flex", "");
        form.put("dataInput", "");
        form.put("csv_trim", "false");
        form.put("printWhatStyle", "printTextOnly");
        form.put("antsPrintStyle", "antsToDoc");
        form.put("antsType", "all");
        return s.postDownload("downloadWithOptions.action", form, maxFileBytes);
    }

    private static void closeAll(BetaViewSession s) {
        try {
            s.getText("closeAllDocuments.action");
        } catch (IOException e) {
            // Aufräumen ist best effort; offene Reiter verfallen mit der Sitzung.
        }
    }

    private KnowledgeDocument document(KnowledgeResource resource, BetaViewTransport.Response download)
            throws IOException {
        byte[] bytes = download.body();
        String contentType = download.header("Content-Type");
        String name = resource.title() + extensionFor(contentType);
        DetectedContentType type = detector.detect(name, contentType,
                Arrays.copyOf(bytes, Math.min(bytes.length, PREFIX_BYTES)));
        ResourceExtractor extractor = extractors.findExtractor(type);
        if (extractor == null) {
            throw new UnsupportedContent(resource.id() + ": kein Extraktor für " + type.mimeType());
        }
        ExtractionResult result = extractor.extract(new ExtractionRequest(resource.id().value(), bytes, name,
                contentType, type));
        if (!result.isSuccess()) {
            throw new UnsupportedContent(resource.id() + ": " + result.errorMessage());
        }
        return KnowledgeDocument.of(resource.toBuilder().contentType(type.mimeType()).build(),
                DocumentText.of(resource.title(), result.document()));
    }

    private static String extensionFor(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (type.contains("pdf")) {
            return ".pdf";
        }
        if (type.contains("html")) {
            return ".html";
        }
        return type.startsWith("text/") || type.isEmpty() ? ".txt" : "";
    }

    private KnowledgeResource resource(BetaViewSession s, BetaViewHtml.OpenedDocument opened, String title) {
        String favId = opened.favId() == null ? selection.favoriteId() : opened.favId();
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("betaview.docid", opened.docId());
        metadata.put("betaview.favid", favId);
        return KnowledgeResource.builder(KnowledgeResourceId.of(SCHEME, locator + "/" + favId + "/" + opened.docId()),
                        sourceId)
                .title(title.isEmpty() ? opened.docId() : title)
                .revision(KnowledgeRevision.version(opened.docId()))
                .scope(sourceId.value())
                .location(s.resolve(openLink(favId, opened.docId())))
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private static String openLink(String favId, String docId) {
        return "opendocumentlink.action?docid=" + docId + "&favid=" + favId;
    }

    /** Titel aus den ersten nicht leeren Zellen der Ergebniszeile. */
    private static String title(BetaViewHtml.ResultRow row) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String value : row.cells().values()) {
            if (value.isEmpty() || used >= TITLE_CELLS) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(value);
            used++;
        }
        return sb.toString();
    }

    /** Favoriten-ID und Dokument-ID; nur Buchstaben und Ziffern. */
    private String[] idsOf(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId.value();
        String[] parts = value.startsWith(prefix) ? value.substring(prefix.length()).split("/") : new String[0];
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9]+") || !parts[1].matches("[A-Za-z0-9]+")) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        return parts;
    }

    // ── Sitzung ──────────────────────────────────────────────────────────────────────────────────────────────

    private interface SessionCall<T> {
        T call(BetaViewSession session) throws IOException;
    }

    /** Führt den Aufruf in der Sitzung aus; scheitert er an der Sitzung, einmal neu anmelden. */
    private <T> T withSession(SessionCall<T> call) throws KnowledgeSourceException {
        boolean fresh = session == null;
        try {
            if (session == null) {
                session = login();
            }
            try {
                return call.call(session);
            } catch (UnsupportedContent e) {
                throw e;
            } catch (IOException e) {
                if (fresh) {
                    throw e;
                }
                session = login();
                return call.call(session);
            }
        } catch (UnsupportedContent e) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, e.getMessage());
        } catch (BetaViewSession.BetaViewLoginException e) {
            session = null;
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "BetaView " + sourceId + ": Anmeldung abgelehnt");
        } catch (BetaViewSession.BetaViewHttpException e) {
            session = null;
            Kind kind = e.status() == 401 || e.status() == 403 ? Kind.ACCESS_DENIED
                    : e.status() == 404 ? Kind.NOT_FOUND : e.status() >= 500 ? Kind.UNAVAILABLE : Kind.INVALID_RESPONSE;
            throw new KnowledgeSourceException(kind, "BetaView " + sourceId + ": HTTP " + e.status());
        } catch (KnowledgeSourceException e) {
            throw e;
        } catch (IOException e) {
            session = null;
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "BetaView " + sourceId + " nicht erreichbar", e);
        }
    }

    private BetaViewSession login() throws IOException, KnowledgeSourceException {
        BetaViewCredentials c;
        try {
            c = credentials.credentials();
        } catch (Exception e) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "BetaView " + sourceId
                    + ": Anmeldedaten nicht verfügbar (KeePass)");
        }
        if (c == null) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "BetaView " + sourceId + ": keine Anmeldedaten");
        }
        try {
            return BetaViewSession.login(base, transport, c);
        } finally {
            c.clear();
        }
    }

    /** Inhalt, den kein Extraktor lesen kann; kein Sitzungsfehler. */
    private static final class UnsupportedContent extends IOException {
        private static final long serialVersionUID = 1L;

        UnsupportedContent(String message) {
            super(message);
        }
    }
}
