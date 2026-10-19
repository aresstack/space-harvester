package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ein FTP-Server als {@link KnowledgeSourcePort}, vor allem MVS/z/OS mit COBOL-Quellen in PDS-Membern.
 *
 * <ul>
 *   <li>Startpunkte: MVS-Datasets oder PDS ({@code HLQ.COBOL.SRC}), einzelne Member ({@code HLQ.COBOL.SRC(PGM1)})
 *       bzw. auf anderen Servern absolute Pfade. Ein PDS liefert seine Member; Datasets darunter werden bis
 *       {@code maxDepth} Ebenen verfolgt ({@code 0} = nur die Member/Dateien direkt im Startpunkt).</li>
 *   <li>IDs: {@code ftp:<Quell-ID>/<Datasetname bzw. Pfad ohne führenden />}.</li>
 *   <li>Inhalt: Text im ASCII-Modus mit Satzstruktur wie MainframeMate, als Codeblock unter dem Datasetnamen.</li>
 *   <li>Revision: Änderungszeit, falls der Server sie meldet, beim Laden zusätzlich der SHA-256 des Texts.</li>
 * </ul>
 */
final class FtpKnowledgeSource implements KnowledgeSourcePort {

    static final String SCHEME = "ftp";

    private final SourceAddress sourceId;
    private final FtpConnectionSettings settings;
    /** {@code host:port} klein, Teil jeder Ressourcen-ID ({@code ftp:host:port/HLQ.DATASET(MEMBER)}). */
    private final String locator;
    private final FtpSessionPool<FtpClientSession> sessions;

    FtpKnowledgeSource(SourceAddress sourceId, FtpConnectionSettings settings,
                       FtpSessionPool<FtpClientSession> sessions) {
        if (sourceId == null || settings == null || sessions == null) {
            throw new IllegalArgumentException("sourceId, settings and sessions are required");
        }
        this.sourceId = sourceId;
        this.settings = settings;
        this.locator = SourceLocators.hostPort(settings.host, settings.port);
        this.sessions = sessions;
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public List<KnowledgeResource> discover(final SourceScope scope) throws KnowledgeSourceException {
        return sessions.run(session -> {
            Map<String, KnowledgeResource> found = new LinkedHashMap<String, KnowledgeResource>();
            Set<String> visited = new LinkedHashSet<String>();
            Deque<Object[]> queue = new ArrayDeque<Object[]>();
            for (String startPoint : scope.startPoints()) {
                String path = normalize(session, startPoint);
                if (path.isEmpty()) {
                    continue;
                }
                if (session.mvs() && new MvsPathDialect().isMemberPath(path)) {
                    add(found, session, path, lastSegment(session, path), 0L, scope);
                    continue;
                }
                List<FtpClientSession.FtpEntry> children = session.list(path);
                visited.add(key(path));
                if (children.isEmpty()) {
                    // Sequentielles Dataset bzw. Datei: der Startpunkt selbst.
                    add(found, session, path, lastSegment(session, path), 0L, scope);
                } else {
                    queue.add(new Object[] {children, 0});
                }
            }
            while (!queue.isEmpty() && found.size() < scope.maxResources()) {
                Object[] next = queue.poll();
                @SuppressWarnings("unchecked")
                List<FtpClientSession.FtpEntry> entries = (List<FtpClientSession.FtpEntry>) next[0];
                int depth = (Integer) next[1];
                for (FtpClientSession.FtpEntry entry : entries) {
                    if (found.size() >= scope.maxResources()) {
                        break;
                    }
                    if (!entry.directory()) {
                        add(found, session, entry.path(), entry.name(), entry.modifiedMillis(), scope);
                    } else if (depth < scope.maxDepth() && visited.add(key(entry.path()))) {
                        List<FtpClientSession.FtpEntry> children;
                        try {
                            children = session.list(entry.path());
                        } catch (CommonsNetFtpSession.FtpNotFoundException e) {
                            continue;
                        }
                        queue.add(new Object[] {children, depth + 1});
                    }
                }
            }
            return new ArrayList<KnowledgeResource>(found.values());
        });
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        final String path = pathOf(resourceId);
        return sessions.run(session -> {
            String remote = session.mvs() ? path : "/" + path;
            String text = session.readText(remote);
            KnowledgeResource resource = resource(path, lastSegment(session, remote), 0L)
                    .toBuilder().revision(KnowledgeRevision.version(sha256(text))).build();
            return KnowledgeDocument.of(resource, "# " + (session.mvs() ? path : remote) + "\n\n```\n" + text + "\n```");
        });
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        pathOf(resourceId);
        return Collections.emptyList();
    }

    /** Trennt die gehaltene Verbindung (z. B. beim Entfernen der Quelle). */
    void close() {
        sessions.close();
    }

    @Override
    public String toString() {
        return "FtpKnowledgeSource{" + sourceId + ", " + settings.host + ":" + settings.port + "}";
    }

    private void add(Map<String, KnowledgeResource> found, FtpClientSession session, String path, String name,
                     long modifiedMillis, SourceScope scope) {
        if (found.size() >= scope.maxResources()) {
            return;
        }
        String idPath = session.mvs() ? MvsQuoteNormalizer.unquote(path) : stripLeadingSlashes(path);
        if (idPath.isEmpty() || found.containsKey(key(idPath))) {
            return;
        }
        try {
            found.put(key(idPath), resource(idPath, name, modifiedMillis));
        } catch (IllegalArgumentException e) {
            // Name ergibt keine gültige Ressourcen-ID: überspringen.
        }
    }

    private KnowledgeResource resource(String idPath, String name, long modifiedMillis) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("ftp.path", idPath);
        KnowledgeResource.Builder builder = KnowledgeResource.builder(idOf(idPath), sourceId)
                .title(name == null || name.isEmpty() ? idPath : name)
                .contentType(KnowledgeResource.DEFAULT_CONTENT_TYPE)
                .revision(modifiedMillis > 0 ? KnowledgeRevision.modifiedAt(Instant.ofEpochMilli(modifiedMillis))
                        : KnowledgeRevision.unknown())
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata));
        URI location = location(idPath);
        if (location != null) {
            builder.location(location);
        }
        return builder.build();
    }

    private URI location(String idPath) {
        try {
            return new URI(SCHEME, null, settings.host, settings.port, "/" + idPath, null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private KnowledgeResourceId idOf(String idPath) {
        return KnowledgeResourceId.of(SCHEME, locator + "/" + idPath);
    }

    private String pathOf(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId == null ? "" : resourceId.value();
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        return value.substring(prefix.length());
    }

    private static String normalize(FtpClientSession session, String startPoint) {
        String trimmed = startPoint == null ? "" : startPoint.trim();
        return session.mvs() ? MvsQuoteNormalizer.unquote(trimmed).toUpperCase(Locale.ROOT) : trimmed;
    }

    private static String lastSegment(FtpClientSession session, String path) {
        if (session.mvs()) {
            String unquoted = MvsQuoteNormalizer.unquote(path);
            int open = unquoted.indexOf('(');
            if (open > 0 && unquoted.endsWith(")")) {
                return unquoted.substring(open + 1, unquoted.length() - 1);
            }
            return MvsDatasetLocator.of(unquoted).displayName();
        }
        String stripped = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = stripped.lastIndexOf('/');
        return slash >= 0 ? stripped.substring(slash + 1) : stripped;
    }

    private static String stripLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }

    private static String key(String path) {
        return path.toUpperCase(Locale.ROOT);
    }

    static String sha256(String text) {
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
