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
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeSystemFile;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectKind;

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
final class NdvKnowledgeSource implements KnowledgeSourcePort {

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

    NdvKnowledgeSource(SourceAddress sourceId, String host, int port, SecretRef credentialRef,
                       SecretProvider secrets, Connector connector) {
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
    public List<KnowledgeResource> discover(final SourceScope scope) throws KnowledgeSourceException {
        return run(client -> {
            Map<String, KnowledgeResource> found = new LinkedHashMap<String, KnowledgeResource>();
            for (String startPoint : scope.startPoints()) {
                String[] parts = startPoint(startPoint);
                if (parts == null) {
                    continue;
                }
                String library = parts[0];
                try {
                    client.logon(library);
                } catch (NdvException e) {
                    // Bibliothek nicht vorhanden oder gesperrt: überspringen.
                    continue;
                }
                List<NdvObjectInfo> objects = client.listObjects(listingSystemFile(client), library, parts[1],
                        ObjectKind.SOURCE, 0);
                for (NdvObjectInfo object : objects) {
                    if (found.size() >= scope.maxResources()) {
                        return new ArrayList<KnowledgeResource>(found.values());
                    }
                    if (!isNaturalSource(object)) {
                        continue;
                    }
                    String path = library + "/" + object.getName() + "." + object.getTypeExtension();
                    if (!found.containsKey(path.toUpperCase(Locale.ROOT))) {
                        try {
                            found.put(path.toUpperCase(Locale.ROOT), resource(path, object,
                                    revision(object.getSourceDate())));
                        } catch (IllegalArgumentException e) {
                            // Name ergibt keine gültige Ressourcen-ID: überspringen.
                        }
                    }
                }
            }
            return new ArrayList<KnowledgeResource>(found.values());
        });
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
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
            try {
                client.logon(library);
            } catch (NdvException e) {
                throw new KnowledgeSourceException(Kind.NOT_FOUND, "Bibliothek " + library + ": " + e.getMessage());
            }
            NdvObjectInfo object = find(client, library, name, extension);
            String text = client.readSource(library, object);
            KnowledgeRevision revision = revision(object.getSourceDate());
            KnowledgeResource resource = resource(path, object, revision.isKnown() ? revision
                    : KnowledgeRevision.version(sha256(text)));
            return KnowledgeDocument.of(resource, "# " + path + "\n\n```natural\n" + text + "\n```");
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
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                        "NDV-Quelle " + sourceId + ": " + e.getMessage(), e);
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
    private static IPalTypeSystemFile listingSystemFile(NdvClient client) throws IOException, NdvException {
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
        if (object.getUser() != null && !object.getUser().isEmpty()) {
            metadata.put("ndv.user", object.getUser());
        }
        return KnowledgeResource.builder(KnowledgeResourceId.of(SCHEME, locator + "/" + path), sourceId)
                .title(object.getName() + "." + object.getTypeExtension())
                .contentType(KnowledgeResource.DEFAULT_CONTENT_TYPE)
                .revision(revision)
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private static KnowledgeRevision revision(String sourceDate) {
        return sourceDate == null || sourceDate.trim().isEmpty() ? KnowledgeRevision.unknown()
                : KnowledgeRevision.version(sourceDate.trim());
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
