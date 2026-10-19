package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dokumentbibliotheken einer SharePoint-Site als {@link KnowledgeSourcePort}.
 *
 * <ul>
 *   <li>Startpunkte: {@code .} = alle sichtbaren Dokumentbibliotheken der Site, sonst Ordner relativ zur Site
 *       ({@code Freigegebene Dokumente/Handbuch}) oder serverrelativ ({@code /sites/team/Dokumente}); Unterordner
 *       bis {@code maxDepth}, der Ordner {@code Forms} einer Bibliothek wird übersprungen.</li>
 *   <li>IDs: {@code sharepoint:<Site-Locator>/<Pfad relativ zur Site>}; Revision: Änderungszeit und Version
 *       ({@code UIVersionLabel}, sonst Größe).</li>
 *   <li>Inhalt: die Datei über {@code $value}, Erkennung und Text über {@code deigma}.</li>
 *   <li>Anmeldung: Anmeldedaten aus dem {@link SharePointCredentialsProvider} je Lauf als Basic-Anmeldung; ohne
 *       Anmeldedaten entscheidet das JDK (Windows-Anmeldung).</li>
 * </ul>
 */
final class SharePointKnowledgeSource implements KnowledgeSourcePort {

    static final String SCHEME = "sharepoint";
    static final String ALL_LIBRARIES = ".";

    private static final String JSON = "application/json;odata=verbose";
    private static final int PREFIX_BYTES = 8192;
    private static final long MAX_JSON_BYTES = 32L * 1024L * 1024L;
    private static final Set<ContentCategory> INDEXABLE = Collections.unmodifiableSet(EnumSet.of(
            ContentCategory.PLAIN_TEXT, ContentCategory.MARKDOWN, ContentCategory.HTML, ContentCategory.PDF,
            ContentCategory.OFFICE_DOCUMENT, ContentCategory.SOURCE_CODE, ContentCategory.STRUCTURED_DATA));

    private final SourceAddress sourceId;
    private final URI siteUri;
    /** Serverrelativer Pfad der Site ohne {@code /} am Ende, z. B. {@code /sites/team}; leer für die Wurzel-Site. */
    private final String sitePath;
    private final String locator;
    private final SharePointCredentialsProvider credentials;
    private final SharePointTransport transport;
    private final ContentDetector detector;
    private final ExtractionRegistry extractors;
    private final long maxFileBytes;

    SharePointKnowledgeSource(SourceAddress sourceId, URI siteUri, SharePointCredentialsProvider credentials,
                              SharePointTransport transport, ContentDetector detector, ExtractionRegistry extractors,
                              long maxFileBytes) {
        if (sourceId == null || siteUri == null || credentials == null || transport == null || detector == null
                || extractors == null) {
            throw new IllegalArgumentException("sourceId, siteUri, credentials, transport, detector and extractors are required");
        }
        this.sourceId = sourceId;
        this.siteUri = siteUri;
        this.sitePath = trimTrailingSlash(siteUri.getPath() == null ? "" : siteUri.getPath());
        this.locator = SharePointSourceProvider.siteLocator(siteUri.toString());
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
    public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        String authorization = authorization();
        List<KnowledgeResource> found = new ArrayList<KnowledgeResource>();
        Set<String> seenFolders = new LinkedHashSet<String>();
        Set<String> seenFiles = new LinkedHashSet<String>();
        Deque<Object[]> queue = new ArrayDeque<Object[]>();
        for (String startPoint : scope.startPoints()) {
            if (ALL_LIBRARIES.equals(startPoint.trim())) {
                for (String library : libraries(authorization)) {
                    queue.add(new Object[] {library, 0, Boolean.TRUE});
                }
            } else {
                queue.add(new Object[] {serverRelative(startPoint), 0, Boolean.TRUE});
            }
        }
        while (!queue.isEmpty() && found.size() < scope.maxResources()) {
            Object[] next = queue.poll();
            String folder = (String) next[0];
            int depth = (Integer) next[1];
            boolean libraryRoot = (Boolean) next[2];
            if (!seenFolders.add(folder.toLowerCase(Locale.ROOT))) {
                continue;
            }
            JsonArray files;
            try {
                files = results(get(api("GetFolderByServerRelativeUrl(" + odataString(folder) + ")/Files"
                        + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel"), authorization));
            } catch (KnowledgeSourceException e) {
                if (e.kind() == Kind.NOT_FOUND) {
                    continue;
                }
                throw e;
            }
            for (JsonElement element : files) {
                if (found.size() >= scope.maxResources()) {
                    break;
                }
                KnowledgeResource resource = resourceOf(element.getAsJsonObject(), true);
                if (resource != null && seenFiles.add(resource.id().value())) {
                    found.add(resource);
                }
            }
            if (depth >= scope.maxDepth()) {
                continue;
            }
            JsonArray folders = results(get(api("GetFolderByServerRelativeUrl(" + odataString(folder) + ")/Folders"
                    + "?$select=Name,ServerRelativeUrl"), authorization));
            for (JsonElement element : folders) {
                JsonObject sub = element.getAsJsonObject();
                String name = string(sub, "Name");
                String path = string(sub, "ServerRelativeUrl");
                if (path == null || name == null || libraryRoot && "Forms".equalsIgnoreCase(name)) {
                    continue;
                }
                queue.add(new Object[] {path, depth + 1, Boolean.FALSE});
            }
        }
        return found;
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String path = serverRelativeOf(resourceId);
        String authorization = authorization();
        String file = "GetFileByServerRelativeUrl(" + odataString(path) + ")";
        JsonObject metadata = (get(api(file + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel"),
                authorization));
        KnowledgeResource resource = resourceOf(metadata, false);
        if (resource == null) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, resourceId + ": unvollständige Dateiangaben");
        }
        long length = number(metadata, "Length");
        if (length > maxFileBytes) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " ist größer als " + maxFileBytes + " Bytes");
        }
        SharePointTransport.Response content = request(api(file + "/$value"), null, authorization, maxFileBytes);
        byte[] bytes = content.body();
        String name = resource.title();
        DetectedContentType type = detector.detect(name, null, Arrays.copyOf(bytes, Math.min(bytes.length, PREFIX_BYTES)));
        ResourceExtractor extractor = extractors.findExtractor(type);
        if (extractor == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + ": kein Extraktor für " + type.mimeType());
        }
        ExtractionResult result = extractor.extract(new ExtractionRequest(resourceId.value(), bytes, name, null, type));
        if (!result.isSuccess()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, resourceId + ": " + result.errorMessage());
        }
        return KnowledgeDocument.of(resource.toBuilder().contentType(type.mimeType()).build(),
                DocumentText.of(name, result.document()));
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        serverRelativeOf(resourceId);
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "SharePointKnowledgeSource{" + sourceId + ", " + locator + "}";
    }

    // ── Bibliotheken, Ressourcen ─────────────────────────────────────────────────────────────────────────────

    /** Serverrelative Wurzelordner aller sichtbaren Dokumentbibliotheken (BaseTemplate 101). */
    private List<String> libraries(String authorization) throws KnowledgeSourceException {
        JsonArray lists = results(get(api("lists?$select=Title,Hidden,BaseTemplate,RootFolder/ServerRelativeUrl"
                + "&$expand=RootFolder&$filter=BaseTemplate%20eq%20101"), authorization));
        List<String> roots = new ArrayList<String>();
        for (JsonElement element : lists) {
            JsonObject list = element.getAsJsonObject();
            if (list.has("Hidden") && list.get("Hidden").isJsonPrimitive() && list.get("Hidden").getAsBoolean()) {
                continue;
            }
            JsonElement root = list.get("RootFolder");
            String path = root != null && root.isJsonObject() ? string(root.getAsJsonObject(), "ServerRelativeUrl") : null;
            if (path != null) {
                roots.add(path);
            }
        }
        return roots;
    }

    /** Ressource einer Datei oder {@code null}, wenn sie nicht zur Site gehört oder (beim Lauf) nicht lesbar ist. */
    private KnowledgeResource resourceOf(JsonObject file, boolean filterByType) {
        String name = string(file, "Name");
        String path = string(file, "ServerRelativeUrl");
        if (name == null || path == null || !path.startsWith(sitePath + "/")) {
            return null;
        }
        long length = number(file, "Length");
        DetectedContentType type = detector.detect(name, null, null);
        if (filterByType && (name.startsWith("~$") || length > maxFileBytes || length == 0
                || !INDEXABLE.contains(type.category()))) {
            return null;
        }
        String relative = path.substring(sitePath.length() + 1);
        Instant modified = instant(string(file, "TimeLastModified"));
        String version = string(file, "UIVersionLabel");
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("sharepoint.path", path);
        metadata.put("file.size", String.valueOf(length));
        KnowledgeResource.Builder builder = KnowledgeResource.builder(KnowledgeResourceId.of(SCHEME, locator + "/" + relative), sourceId)
                .title(name)
                .contentType(type.mimeType())
                .revision(modified == null ? KnowledgeRevision.version(version == null ? String.valueOf(length) : version)
                        : KnowledgeRevision.of(modified, version == null ? String.valueOf(length) : version))
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata));
        URI location = location(path);
        if (location != null) {
            builder.location(location);
        }
        return builder.build();
    }

    private URI location(String serverRelative) {
        try {
            return new URI(siteUri.getScheme(), null, siteUri.getHost(), siteUri.getPort(), serverRelative, null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String serverRelative(String startPoint) {
        String trimmed = trimTrailingSlash(startPoint.trim().replace('\\', '/'));
        if (trimmed.startsWith("/")) {
            return trimmed;
        }
        return sitePath + "/" + trimmed;
    }

    private String serverRelativeOf(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId.value();
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        String relative = value.substring(prefix.length());
        if (relative.contains("/../") || relative.startsWith("../") || relative.endsWith("/..")) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        return sitePath + "/" + relative;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Basic-Anmeldung aus frischen Anmeldedaten; {@code null} ohne Anmeldedaten. */
    private String authorization() throws KnowledgeSourceException {
        SharePointCredentials c;
        try {
            c = credentials.credentials();
        } catch (Exception e) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "SharePoint " + sourceId
                    + ": Anmeldedaten nicht verfügbar (KeePass)");
        }
        if (c == null) {
            return null;
        }
        try {
            java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(c.password()));
            byte[] user = (c.username() + ":").getBytes(StandardCharsets.UTF_8);
            byte[] joined = new byte[user.length + encoded.remaining()];
            System.arraycopy(user, 0, joined, 0, user.length);
            encoded.get(joined, user.length, encoded.limit());
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
            String header = "Basic " + Base64.getEncoder().encodeToString(joined);
            Arrays.fill(joined, (byte) 0);
            return header;
        } finally {
            c.clear();
        }
    }

    private URI api(String relative) throws KnowledgeSourceException {
        try {
            return new URI(trimTrailingSlash(siteUri.toString()) + "/_api/web/" + relative);
        } catch (URISyntaxException e) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint " + sourceId + ": ungültiger Pfad", e);
        }
    }

    private JsonObject get(URI uri, String authorization) throws KnowledgeSourceException {
        SharePointTransport.Response response = request(uri, JSON, authorization, MAX_JSON_BYTES);
        try {
            JsonElement root = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("d") || !root.getAsJsonObject().get("d").isJsonObject()) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint " + sourceId
                        + ": unerwartete Antwort (kein OData-JSON)");
            }
            return root.getAsJsonObject().getAsJsonObject("d");
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint " + sourceId
                    + ": unerwartete Antwort (kein JSON)", e);
        }
    }

    private SharePointTransport.Response request(URI uri, String accept, String authorization, long maxBytes)
            throws KnowledgeSourceException {
        SharePointTransport.Response response;
        try {
            response = transport.get(uri, accept, authorization, maxBytes);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint " + sourceId + " nicht erreichbar", e);
        }
        int status = response.status();
        if (status == 401 || status == 403) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "SharePoint " + sourceId
                    + ": Zugriff verweigert (HTTP " + status + ")");
        }
        if (status == 404) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "SharePoint " + sourceId + ": nicht gefunden");
        }
        if (status >= 500) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint " + sourceId + ": HTTP " + status);
        }
        if (status / 100 != 2) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint " + sourceId + ": HTTP " + status);
        }
        return response;
    }

    // ── JSON ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static JsonArray results(JsonObject d) {
        JsonElement results = d.get("results");
        return results != null && results.isJsonArray() ? results.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static long number(JsonObject object, String key) {
        String value = string(object, key);
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Serverrelativer Pfad als OData-Zeichenkette in der URL: {@code '} verdoppelt, alles außer unreservierten
     * Zeichen, {@code /} und {@code '} prozentkodiert (UTF-8).
     */
    static String odataString(String path) {
        String quoted = "'" + path.replace("'", "''") + "'";
        StringBuilder sb = new StringBuilder();
        for (byte b : quoted.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.'
                    || c == '_' || c == '~' || c == '/' || c == '\'') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
