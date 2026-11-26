package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.LibraryListingSource;
import com.aresstack.enterpriseai.source.api.InventoryCoverageSource;
import com.aresstack.enterpriseai.source.api.NavigationResourceSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import java.time.Instant;
import java.util.Base64;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeSystemFile;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectKind;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Natural-Quellen eines NDV-Servers (Natural Development Server, NATSPOD) als {@link KnowledgeSourcePort}.
 *
 * <ul>
 *   <li>Startpunkte: Bibliotheken ({@code MYLIB}) oder Bibliothek mit Namensfilter ({@code MYLIB/CUST*}).</li>
 *   <li>IDs: {@code ndv:<Quell-ID>/<BIBLIOTHEK>/<OBJEKT>.<Endung>} (Endung wie NaturalONE: NSP, NSN, NSC ...).</li>
 *   <li>Inhalt: Quelltext als Codeblock unter {@code BIBLIOTHEK/OBJEKT.Endung}; Revision = SHA-256 des Texts bzw.
 *       Änderungsdatum aus der Auflistung.</li>
 * </ul>
 *
 * <p>Auswahl der Objekte, Systemdatei und Download wie MainframeMate ({@code NdvSourceScanner},
 * {@code NdvService.findObject}, {@code NdvClient.readSource}). Eine Verbindung je Quelle wird wiederverwendet;
 * Benutzer und Passwort kommen nur beim (Wieder-)Verbinden über den {@link SecretProvider}.
 */
final class NdvKnowledgeSource implements KnowledgeSourcePort, LibraryListingSource, NavigationResourceSource,
        InventoryCoverageSource, StoredContentSource {

    static final String SCHEME = "ndv";

    /** Öffnet eine angemeldete NDV-Verbindung (Naht für Tests). */
    interface Connector {
        NdvClient open(String host, int port, String user, String password) throws IOException;
    }

    /** Der Server hat Verbindung oder Anmeldung abgelehnt (NDV-Meldung, kein Netzfehler). */
    static final class NdvLoginException extends IOException {
        private static final long serialVersionUID = 1L;

        NdvLoginException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private interface Operation<T> {
        T run(NdvClient client) throws IOException, NdvException, KnowledgeSourceException;
    }

    private final SourceAddress sourceId;
    private final String host;
    private final int port;
    /** {@code host:port} klein, Teil jeder Ressourcen-ID ({@code ndv:host:port/LIB/OBJ.NSP}). */
    private final String locator;
    private final SecretRef credentialRef;
    private final SecretProvider secrets;
    private final Connector connector;
    private NdvClient client;
    private IPalTypeSystemFile listing;
    private final NdvInventoryMetadata inventory;
    private final boolean ddmsEnabled;
    private final boolean allLibraries;
    private final boolean navigationFill;
    private volatile NdvInventoryCoverage coverage = NdvInventoryCoverage.empty();
    /** Der eingestellte Umfang der letzten Inventur; {@code null} für alle Bibliotheken oder ohne Inventur. */
    private volatile NdvInventoryCoverage inventoryScope;

    // Bound the reuse to one recent inventory and one download per object.
    private static final int MAX_INVENTORY_METADATA = 10000;
    private static final long METADATA_AGE_NANOS = TimeUnit.MINUTES.toNanos(5);

    NdvKnowledgeSource(SourceAddress sourceId, String host, int port, SecretRef credentialRef,
                       SecretProvider secrets, Connector connector) {
        this(sourceId, host, port, credentialRef, secrets, connector, false, System::nanoTime);
    }

    NdvKnowledgeSource(SourceAddress sourceId, String host, int port, SecretRef credentialRef,
                       SecretProvider secrets, Connector connector, LongSupplier nanoTime) {
        this(sourceId, host, port, credentialRef, secrets, connector, false, nanoTime);
    }

    NdvKnowledgeSource(SourceAddress sourceId, String host, int port, SecretRef credentialRef,
                       SecretProvider secrets, Connector connector, boolean ddmsEnabled, LongSupplier nanoTime) {
        this(sourceId, host, port, credentialRef, secrets, connector, ddmsEnabled, false, false, nanoTime);
    }

    NdvKnowledgeSource(SourceAddress sourceId, String host, int port, SecretRef credentialRef,
                       SecretProvider secrets, Connector connector, boolean ddmsEnabled,
                       boolean allLibraries, boolean navigationFill, LongSupplier nanoTime) {
        this.ddmsEnabled = ddmsEnabled;
        this.allLibraries = allLibraries;
        this.navigationFill = navigationFill;
        this.inventory = new NdvInventoryMetadata(MAX_INVENTORY_METADATA, METADATA_AGE_NANOS, nanoTime);
        if (sourceId == null || host == null || credentialRef == null || secrets == null || connector == null) {
            throw new IllegalArgumentException("sourceId, host, credentialRef, secrets and connector are required");
        }
        this.sourceId = sourceId;
        this.host = host;
        this.port = port;
        this.locator = SourceLocators.hostPort(host, port);
        this.credentialRef = credentialRef;
        this.secrets = secrets;
        this.connector = connector;
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public List<String> libraries() throws KnowledgeSourceException {
        return run(this::listLibraries);
    }

    private List<String> listLibraries(NdvClient client) throws IOException, NdvException {
        java.util.Set<String> names = new java.util.TreeSet<String>();
        for (String name : client.listLibraries(listingSystemFile(client), "*")) {
            if (name != null && !name.trim().isEmpty()) {
                names.add(name.trim().toUpperCase(Locale.ROOT));
            }
        }
        return new ArrayList<String>(names);
    }

    @Override
    public KnowledgeResourceId navigationResource(String nativePath) throws KnowledgeSourceException {
        if (!navigationFill || nativePath == null) {
            return null;
        }
        String path = nativePath.trim().toUpperCase(Locale.ROOT);
        int slash = path.indexOf('/');
        int dot = path.lastIndexOf('.');
        if (slash <= 0 || slash != path.lastIndexOf('/') || dot <= slash + 1
                || dot == path.length() - 1 || path.indexOf('*') >= 0 || path.indexOf('?') >= 0
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.contains("..")) {
            return null;
        }
        String extension = path.substring(dot + 1);
        if (ObjectType.DDM_EXT.equalsIgnoreCase(extension)
                || !(extension.startsWith("NS") || extension.equals("NAT"))) {
            return null;
        }
        // Resolve the identity without an extra per-object listing; load checks access and resolves metadata.
        return KnowledgeResourceId.of(SCHEME, locator + "/" + path);
    }

    @Override
    public List<KnowledgeResource> discover(final SourceScope scope) throws KnowledgeSourceException {
        coverage = NdvInventoryCoverage.empty();
        inventoryScope = allLibraries ? null : new NdvInventoryCoverage(scope.startPoints());
        if (!allLibraries && scope.startPoints().isEmpty()) {
            return Collections.emptyList();
        }
        return run(client -> {
            inventory.clear();
            Map<String, KnowledgeResource> found = new LinkedHashMap<String, KnowledgeResource>();
            java.util.Set<String> startPoints = new java.util.LinkedHashSet<String>();
            if (allLibraries) {
                startPoints.addAll(listLibraries(client));
                for (String point : scope.startPoints()) {
                    if (NdvDdmScope.isStartPoint(point)) {
                        startPoints.add(point);
                    }
                }
            } else {
                startPoints.addAll(scope.startPoints());
            }
            for (String startPoint : startPoints) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("NDV inventory interrupted");
                }
                if (NdvDdmScope.isStartPoint(startPoint)) {
                    if (!ddmsEnabled) {
                        throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "FDDM ist nicht für diese Quelle konfiguriert");
                    }
                    discoverDdms(client, scope, found);
                    continue;
                }
                String[] parts = startPoint(startPoint);
                if (parts == null) {
                    continue;
                }
                String library = parts[0];
                ensureLibrary(client, library);
                List<NdvObjectInfo> objects = client.listObjects(listingSystemFile(client), library, parts[1],
                        ObjectKind.SOURCE, 0);
                for (NdvObjectInfo object : objects) {
                    if (!isNaturalSource(object) || object.getType() == ObjectType.DDM) {
                        continue;
                    }
                    addResource(scope, found, library, object);
                }
            }
            coverage = new NdvInventoryCoverage(startPoints);
            return new ArrayList<KnowledgeResource>(found.values());
        });
    }

    @Override
    public boolean inventoryCovers(KnowledgeResourceId resourceId) {
        try {
            return coverage.covers(pathOf(resourceId));
        } catch (KnowledgeSourceException e) {
            return false;
        }
    }

    /** Ob die Ressource im eingestellten Umfang der letzten Inventur liegt; alle Bibliotheken oder unbekannt: ja. */
    @Override
    public boolean withinInventoryScope(KnowledgeResourceId resourceId) {
        NdvInventoryCoverage scope = inventoryScope;
        if (scope == null) {
            return true;
        }
        try {
            return scope.covers(pathOf(resourceId));
        } catch (KnowledgeSourceException e) {
            return true;
        }
    }

    private void discoverDdms(NdvClient client, SourceScope scope, Map<String, KnowledgeResource> found)
            throws IOException, NdvException, KnowledgeSourceException {
        for (IPalTypeSystemFile file : NdvDdmScope.systemFiles(client)) {
            String area = NdvDdmScope.area(file);
            for (NdvObjectInfo object : client.listObjects(file, "", "*", ObjectKind.SOURCE, ObjectType.DDM)) {
                if (object.getType() == ObjectType.DDM) {
                    addResource(scope, found, area, NdvDdmScope.inFile(object, file));
                }
            }
        }
    }

    private void addResource(SourceScope scope, Map<String, KnowledgeResource> found, String area,
                             NdvObjectInfo object) throws KnowledgeSourceException {
        String path = area + "/" + object.getEffectiveName() + "." + object.getTypeExtension();
        String key = path.toUpperCase(Locale.ROOT);
        if (found.containsKey(key)) {
            return;
        }
        if (found.size() >= scope.maxResources()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                    "NDV-Inventur überschreitet maxResources (" + scope.maxResources()
                            + "). Bereich verkleinern oder Grenze erhöhen.");
        }
        try {
            found.put(key, resource(path, object, revision(object)));
            inventory.remember(path, object);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Ungültige NDV-Objektidentität", e);
        }
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String path = pathOf(resourceId);
        StoredContent content = readContent(resourceId);
        return KnowledgeDocument.of(content.resource(), "# " + path + "\n\n```natural\n"
                + new String(content.payload(), StandardCharsets.UTF_8) + "\n```");
    }

    @Override
    public String localId(KnowledgeResourceId resourceId) {
        try {
            pathOf(resourceId);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(resourceId.value().getBytes(StandardCharsets.UTF_8));
        } catch (KnowledgeSourceException invalid) {
            return null;
        }
    }

    @Override
    public StoredContent storedContent(String localId) throws KnowledgeSourceException {
        KnowledgeResourceId resourceId;
        try {
            resourceId = KnowledgeResourceId.of(new String(Base64.getUrlDecoder().decode(localId),
                    StandardCharsets.UTF_8));
            if (!localId.equals(localId(resourceId))) throw new IllegalArgumentException("Invalid resource ID");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid NDV resource ID");
        }
        return readContent(resourceId);
    }

    private StoredContent readContent(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        final String path = pathOf(resourceId);
        int slash = path.indexOf('/');
        int dot = path.lastIndexOf('.');
        if (slash <= 0 || dot <= slash + 1) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " ist keine NDV-Objekt-ID");
        }
        final String library = path.substring(0, slash);
        final String name = path.substring(slash + 1, dot);
        final String extension = path.substring(dot + 1);
        return run(client -> {
            boolean ddm = NdvDdmScope.isArea(library);
            if (ddm && (!ddmsEnabled || !ObjectType.DDM_EXT.equalsIgnoreCase(extension))) {
                throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "DDM ist nicht im konfigurierten Bereich");
            }
            if (!ddm) {
                if (ObjectType.DDM_EXT.equalsIgnoreCase(extension)) {
                    throw new KnowledgeSourceException(Kind.UNSUPPORTED, "DDMs benötigen eine FDDM-Identität");
                }
                ensureLibrary(client, library);
            }
            NdvObjectInfo object = inventory.take(path);
            if (object == null) {
                object = ddm ? NdvDdmScope.find(client, library, name) : find(client, library, name, extension);
            }
            String text = client.readSource(ddm ? "" : library, object);
            KnowledgeRevision revision = revision(object);
            KnowledgeResource resource = resource(path, object, revision.isKnown() ? revision
                    : KnowledgeRevision.version(sha256(text)));
            Map<String, String> metadata = new LinkedHashMap<String, String>(resource.metadata().asMap());
            metadata.put(NdvSourceProvider.SOURCE_HASH, sha256(text));
            resource = resource.toBuilder().metadata(KnowledgeMetadata.of(metadata)).build();
            return new StoredContent(resource, text.getBytes(StandardCharsets.UTF_8), "text/plain",
                    NdvSourceProvider.SOURCE_TEXT_FORMAT, Instant.now());
        });
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        pathOf(resourceId);
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "NdvKnowledgeSource{" + sourceId + ", " + host + ":" + port + "}";
    }

    // --- Verbindung ---------------------------------------------------------------------------------------

    private synchronized <T> T run(Operation<T> operation) throws KnowledgeSourceException {
        for (int attempt = 0; ; attempt++) {
            if (client == null || !client.isConnected()) {
                closeClient();
                client = connect();
            }
            try {
                return operation.run(client);
            } catch (NdvException e) {
                inventory.clear();
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                        "NDV-Quelle " + sourceId + ": " + e.getMessage(), e);
            } catch (KnowledgeSourceException e) {
                inventory.clear();
                throw e;
            } catch (IOException e) {
                closeClient();
                if (attempt > 0) {
                    throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                            "NDV-Quelle " + sourceId + " (" + host + ":" + port + "): " + e.getMessage(), e);
                }
            }
        }
    }

    private NdvClient connect() throws KnowledgeSourceException {
        try {
            return secrets.withSecret(credentialRef, (SecretMaterial material) -> {
                char[] password = material.copySecret();
                try {
                    return connector.open(host, port, material.principal(), new String(password));
                } finally {
                    Arrays.fill(password, '\0');
                }
            });
        } catch (SecretUnavailableException e) {
            Kind kind = e.reason() == SecretUnavailableException.Reason.NOT_AVAILABLE ? Kind.UNAVAILABLE : Kind.ACCESS_DENIED;
            throw new KnowledgeSourceException(kind, "Anmeldedaten für NDV-Quelle " + sourceId + " nicht verfügbar ("
                    + e.reason() + ", " + credentialRef + ")");
        } catch (NdvLoginException e) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "NDV-Quelle " + sourceId + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                    "NDV-Quelle " + sourceId + " (" + host + ":" + port + ") nicht erreichbar: " + e.getMessage(), e);
        }
    }

    private void closeClient() {
        inventory.clear();
        listing = null;
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                // Verbindung ist ohnehin weg.
            }
            client = null;
        }
    }

    // --- Objekte ------------------------------------------------------------------------------------------

    private static void ensureLibrary(NdvClient client, String library) throws IOException, NdvException {
        if (!library.equals(client.getCurrentLibrary())) {
            client.logon(library);
        }
    }

    /** Wie {@code NdvService.findObject}: mit dem Namen als Filter auflisten, sonst Objekt aus der Endung. */
    private NdvObjectInfo find(NdvClient client, String library, String name, String extension)
            throws IOException, NdvException {
        List<NdvObjectInfo> objects = client.listObjects(listingSystemFile(client), library, name,
                ObjectKind.SOURCE, 0);
        for (NdvObjectInfo object : objects) {
            if (name.equalsIgnoreCase(object.getName()) && extension.equalsIgnoreCase(object.getTypeExtension())) {
                return object;
            }
        }
        return NdvObjectInfo.forBookmark(name, extension);
    }

    /** Systemdatei für Auflistungen wie {@code NdvService.getListingSysFile}: FUSER, sonst erste gültige. */
    private IPalTypeSystemFile listingSystemFile(NdvClient client) throws IOException, NdvException {
        if (listing == null) {
            listing = selectListingSystemFile(client);
        }
        return listing;
    }

    private static IPalTypeSystemFile selectListingSystemFile(NdvClient client) throws IOException, NdvException {
        IPalTypeSystemFile[] files = client.getSystemFiles();
        if (files == null || files.length == 0) {
            return client.getDefaultSystemFile();
        }
        IPalTypeSystemFile firstValid = null;
        for (IPalTypeSystemFile file : files) {
            if (file.getKind() == IPalTypeSystemFile.FUSER && file.getDatabaseId() > 0 && file.getFileNumber() > 0) {
                return file;
            }
            if (firstValid == null && file.getDatabaseId() > 0 && file.getFileNumber() > 0) {
                firstValid = file;
            }
        }
        return firstValid != null ? firstValid : files[0];
    }

    /** Wie {@code NdvSourceScanner.isNaturalSourceType}. */
    private static boolean isNaturalSource(NdvObjectInfo object) {
        String extension = object.getTypeExtension();
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        String upper = extension.toUpperCase(Locale.ROOT);
        return upper.startsWith("NS") || upper.equals("NAT");
    }

    private KnowledgeResource resource(String path, NdvObjectInfo object, KnowledgeRevision revision) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("ndv.library", path.substring(0, path.indexOf('/')));
        metadata.put("ndv.type", object.getTypeName());
        metadata.put("ndv.dbid", Integer.toString(object.getDatabaseId()));
        metadata.put("ndv.fnr", Integer.toString(object.getFileNumber()));
        if (object.getUser() != null && !object.getUser().isEmpty()) {
            metadata.put("ndv.user", object.getUser());
        }
        return KnowledgeResource.builder(KnowledgeResourceId.of(SCHEME, locator + "/" + path), sourceId)
                .title(object.getEffectiveName() + "." + object.getTypeExtension())
                .contentType(KnowledgeResource.DEFAULT_CONTENT_TYPE)
                .revision(revision)
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private static KnowledgeRevision revision(NdvObjectInfo object) {
        String date = object.getSourceDate();
        if (date == null || date.trim().isEmpty()) {
            return KnowledgeRevision.unknown();
        }
        return KnowledgeRevision.version(date.trim() + "|size=" + object.getSourceSize());
    }

    /** {@code [Bibliothek, Filter]} oder {@code null}. */
    private static String[] startPoint(String value) {
        String trimmed = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            return new String[] {trimmed, "*"};
        }
        String library = trimmed.substring(0, slash).trim();
        String filter = trimmed.substring(slash + 1).trim();
        return library.isEmpty() ? null : new String[] {library, filter.isEmpty() ? "*" : filter};
    }

    private String pathOf(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId == null ? "" : resourceId.value();
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        return value.substring(prefix.length());
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
