package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Issue;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Link;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Project;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.SearchResult;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Session;
import com.aresstack.enterpriseai.source.api.AddressableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.RestQuerySource;
import com.aresstack.enterpriseai.source.api.RestResponse;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jira Data Center als {@link SearchableKnowledgeSource}, ausschließlich lesend.
 *
 * <ul>
 *   <li>Ohne Startpunkte ({@code project:*}, Standard): alle Vorgänge aller Projekte außer den abgehakten
 *       ({@link SourceScope#excludedPartitions()}); die Projekte listet {@link #partitions()}.</li>
 *   <li>Startpunkte (nur aus der Datei): Projekt ({@code project:KEY}), Vorgang ({@code KEY-123}; {@code discover}
 *       folgt dann Verknüpfungen, Teilvorgängen und dem übergeordneten Vorgang bis {@code maxDepth}), JQL
 *       ({@code jql:…}) oder eine Ressourcen-ID dieser Quelle.</li>
 *   <li>IDs: {@code jira:<locator>/issue/<KEY-123>} (Locator = kanonische Basis-URL ohne Schema).</li>
 *   <li>Revision: Zeitpunkt der letzten Änderung ({@code updated}); Jira hat keine Versionsnummer je Vorgang.</li>
 *   <li>Inhalt ({@link #load}): Titel, Metadaten, Beschreibung, Umgebung, Verknüpfungen und Kommentare als Text mit
 *       Markdown-Struktur ({@link JiraIssueText}); gerenderte Felder in Text überführt.</li>
 *   <li>Lese-Ansicht ({@link PageViewSource}): dieselben Angaben als HTML mit den von Jira gerenderten Feldern und
 *       zuletzt den Rohdaten (die unveränderte JSON-Antwort) ({@link JiraIssueView}); Bilder lädt
 *       {@link #loadViewImage} angemeldet von dieser Jira, Anhänge {@link #loadAttachment}.</li>
 *   <li>Suche: JQL {@code text ~ "…"} (Titel: {@code summary ~ "…"}), eingeschränkt auf gewählte Projekte und die
 *       konfigurierte Zusatzbedingung, jüngste Änderung zuerst; jeder Treffer trägt sein Projekt als Partition.</li>
 *   <li>{@link PartitionListingSource}: alle Vorgänge eines Projekts flach über {@code /search}, Cursor an das Projekt
 *       gebunden; {@link StoredContentSource}: die JSON-Antwort eines Vorgangs unverändert (Raw-First);
 *       {@link RestQuerySource}: beliebige GET-Abfragen unter {@code /rest/} mit der Anmeldung der Quelle.</li>
 * </ul>
 *
 * <p>Anmeldedaten löst der Adapter je Port-Aufruf genau einmal über den {@link SecretProvider} auf und vergisst sie
 * mit dem Ende des Aufrufs. Ohne {@link JiraConfig#credentialRef()} fragt er keine an (z. B. reine mTLS-Anmeldung).
 */
public class JiraKnowledgeSource implements SearchableKnowledgeSource, PageViewSource, AddressableKnowledgeSource,
        PartitionListingSource, ResourceMetadataSource, StoredContentSource, RestQuerySource, JiraIssueOperations {

    static final String ISSUE_CONTENT_TYPE = "text/markdown";
    /** {@link StoredContent#nativeFormat()} der Vorgangs-Snapshots: REST-Antwort von {@code /issue} mit gerenderten Feldern. */
    static final String SNAPSHOT_FORMAT = "jira-rest-issue+json;expand=" + JiraRestClient.ISSUE_EXPAND;
    /** Größte Datei (Bild, Anhang), die der Lesebereich lädt (50 MB). */
    static final int READER_MAX_BYTES = 50 * 1024 * 1024;
    /** Höchstens so viele Vorgänge fordert ein Stapel von {@link #resources} an. */
    static final int MAX_PARTITION_BATCH = 200;
    /** Länge des Ausschnitts eines Suchtreffers. */
    private static final int SNIPPET_LENGTH = 240;

    private static final Logger LOG = Logger.getLogger(JiraKnowledgeSource.class.getName());
    private static final String PARTITION_CURSOR_PREFIX = "p1.";
    private static final Pattern PARTITION_CURSOR = Pattern.compile("p1\\.([A-Z][A-Z0-9_]{0,254})\\.([0-9]{1,9})");
    private static final Pattern BROWSE = Pattern.compile("/browse/([A-Z][A-Z0-9_]*-[0-9]{1,12})(?:/.*)?");
    private static final Pattern PROJECT_ISSUE = Pattern.compile("/projects/[^/]+/issues/([A-Z][A-Z0-9_]*-[0-9]{1,12})(?:/.*)?");
    /** Kundenportal des Service Desk: {@code /servicedesk/customer/portal/<Portal-Nr.>/<KEY-1>}. */
    private static final Pattern PORTAL_ISSUE = Pattern.compile(
            "/servicedesk/customer/portal/[0-9]{1,9}/([A-Z][A-Z0-9_]*-[0-9]{1,12})(?:/.*)?");
    private static final Pattern SELECTED_ISSUE = Pattern.compile("(?:^|&)selectedIssue=([A-Z][A-Z0-9_]*-[0-9]{1,12})(?:&|$)");

    private final SourceAddress sourceId;
    /** Kanonische Basis-URL ohne Schema, Teil jeder Ressourcen-ID. */
    private final String locator;
    private final JiraConfig config;
    private final JiraRestClient client;
    private final SecretProvider secrets;
    private final VaultEntryOptions options;

    /**
     * @param transport z. B. {@link UrlConnectionJiraTransport} mit Proxy/mTLS aus der Composition Root
     * @param secrets   Security-Port; wird nur benutzt, wenn {@link JiraConfig#credentialRef()} gesetzt ist
     */
    public JiraKnowledgeSource(SourceAddress sourceId, JiraConfig config, JiraHttpTransport transport,
                               SecretProvider secrets) {
        this(sourceId, config, transport, secrets, new VaultEntryOptions(secrets, config == null ? null
                : config.credentialRef()));
    }

    JiraKnowledgeSource(SourceAddress sourceId, JiraConfig config, JiraHttpTransport transport, SecretProvider secrets,
                        VaultEntryOptions options) {
        if (sourceId == null || config == null || transport == null) {
            throw new IllegalArgumentException("sourceId, config und transport sind Pflicht");
        }
        if (config.credentialRef() != null && secrets == null) {
            throw new IllegalArgumentException("credentialRef gesetzt, aber kein SecretProvider");
        }
        this.sourceId = sourceId;
        this.locator = SourceLocators.url(config.baseUrl().toString());
        this.config = config;
        this.client = new JiraRestClient(config, transport);
        this.secrets = secrets;
        this.options = options;
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    // --- KnowledgeSourcePort ------------------------------------------------------------------------------------

    @Override
    public List<KnowledgeResource> discover(final SourceScope scope) throws KnowledgeSourceException {
        return withSession(new Operation<List<KnowledgeResource>>() {
            @Override
            public List<KnowledgeResource> run(Session session) throws KnowledgeSourceException {
                return crawl(session, scope);
            }
        });
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        final String key = key(resourceId);
        return withSession(new Operation<KnowledgeDocument>() {
            @Override
            public KnowledgeDocument run(Session session) throws KnowledgeSourceException {
                Issue issue = fullIssue(session, key);
                return KnowledgeDocument.of(resource(issue), JiraIssueText.of(issue));
            }
        });
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        final String key = key(resourceId);
        return withSession(new Operation<List<SourceLink>>() {
            @Override
            public List<SourceLink> run(Session session) throws KnowledgeSourceException {
                Issue issue = client.issue(session, key);
                Map<KnowledgeResourceId, SourceLink> links = new LinkedHashMap<KnowledgeResourceId, SourceLink>();
                for (Link link : issue.links) {
                    KnowledgeResourceId target = JiraIds.issue(locator, link.key);
                    if (!links.containsKey(target)) {
                        String label = link.relation.isEmpty() ? link.key : link.relation + " " + link.key;
                        links.put(target, new SourceLink(target, link.summary.isEmpty() ? label
                                : label + ": " + link.summary));
                    }
                }
                return new ArrayList<SourceLink>(links.values());
            }
        });
    }

    @Override
    public List<SourcePartition> partitions() throws KnowledgeSourceException {
        return withSession(new Operation<List<SourcePartition>>() {
            @Override
            public List<SourcePartition> run(Session session) throws KnowledgeSourceException {
                List<SourcePartition> partitions = new ArrayList<SourcePartition>();
                for (Project project : client.projects(session)) {
                    partitions.add(new SourcePartition(project.key, project.name, null,
                            JiraIds.PROJECT_PREFIX + project.key));
                }
                return partitions;
            }
        });
    }

    // --- SearchableKnowledgeSource ------------------------------------------------------------------------------

    @Override
    public List<SourceSearchHit> search(final SourceQuery query) throws KnowledgeSourceException {
        final String jql = searchJql(query);
        return withSession(new Operation<List<SourceSearchHit>>() {
            @Override
            public List<SourceSearchHit> run(Session session) throws KnowledgeSourceException {
                List<SourceSearchHit> hits = new ArrayList<SourceSearchHit>();
                SearchResult result = client.search(session, jql, 0, query.limit(), JiraRestClient.SEARCH_FIELDS);
                for (Issue issue : result.issues) {
                    hits.add(new SourceSearchHit(JiraIds.issue(locator, issue.key), issue.title(), snippet(issue),
                            revision(issue), browse(issue.key), issue.projectKey));
                }
                return hits;
            }
        });
    }

    /** Die JQL einer Suche: Textbedingung, gewählte und abgewählte Projekte, Zusatzbedingung, jüngste Änderung zuerst. */
    String searchJql(SourceQuery query) {
        List<String> clauses = new ArrayList<String>();
        clauses.add((query.titleOnly() ? "summary" : "text") + " ~ " + jqlString(query.text()));
        List<String> included = projectKeys(query.includedPartitions());
        if (!included.isEmpty()) {
            clauses.add("project in (" + String.join(", ", included) + ")");
        }
        List<String> excluded = projectKeys(query.excludedPartitions());
        if (!excluded.isEmpty()) {
            clauses.add("project not in (" + String.join(", ", excluded) + ")");
        }
        return withFilter(String.join(" AND ", clauses)) + " ORDER BY updated DESC";
    }

    /** Am Schluss einer freien JQL-Abfrage: {@code ORDER BY …} ohne Anführungszeichen. */
    private static final Pattern ORDER_BY = Pattern.compile("(?i)\\s*\\bORDER\\s+BY\\b[^\"']*$");

    /**
     * Eine freie JQL-Abfrage (Werkzeug {@code jira_search}) mit der Einschränkung der Quelle: die Bedingung in Klammern,
     * damit ein {@code OR} darin die Einschränkung nicht aufhebt; {@code ORDER BY} bleibt am Schluss.
     */
    private String restricted(String jql) {
        Matcher order = ORDER_BY.matcher(jql);
        boolean sorted = order.find();
        String condition = sorted ? jql.substring(0, order.start()).trim() : jql;
        String sort = sorted ? " " + jql.substring(order.start()).trim() : "";
        if (condition.isEmpty()) {
            return config.jqlFilter().isEmpty() ? sort.trim() : config.jqlFilter() + sort;
        }
        return withFilter("(" + condition + ")") + sort;
    }

    private String withFilter(String jql) {
        return config.jqlFilter().isEmpty() ? jql : "(" + config.jqlFilter() + ") AND " + jql;
    }

    private static List<String> projectKeys(Collection<String> keys) {
        List<String> result = new ArrayList<String>();
        for (String key : keys) {
            String upper = key.trim().toUpperCase(Locale.ROOT);
            if (JiraIds.isProjectKey(upper) && !result.contains(upper)) {
                result.add(upper);
            }
        }
        return result;
    }

    /** Ein JQL-Stringliteral: Anführungszeichen und Rückstriche maskiert. */
    static String jqlString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String snippet(Issue issue) {
        StringBuilder sb = new StringBuilder();
        if (issue.issueType != null) {
            sb.append(issue.issueType);
        }
        if (issue.status != null) {
            sb.append(sb.length() > 0 ? " · " : "").append(issue.status);
        }
        String description = issue.description == null ? "" : issue.description.replaceAll("[\\s{}*_#|!]+", " ").trim();
        if (!description.isEmpty()) {
            sb.append(sb.length() > 0 ? " — " : "");
            sb.append(description.length() > SNIPPET_LENGTH ? description.substring(0, SNIPPET_LENGTH) + "…"
                    : description);
        }
        return sb.toString();
    }

    // --- PageViewSource -----------------------------------------------------------------------------------------

    @Override
    public PageView pageView(KnowledgeResourceId page) throws KnowledgeSourceException {
        final String key = key(page);
        return withSession(new Operation<PageView>() {
            @Override
            public PageView run(Session session) throws KnowledgeSourceException {
                Issue issue = fullIssue(session, key);
                String base = base();
                JiraIssueView view = JiraIssueView.of(issue, base);
                List<PageView.Image> images = new ArrayList<PageView.Image>();
                for (String src : view.images) {
                    images.add(new PageView.Image(src, "", null));
                }
                List<PageView.Attachment> attachments = new ArrayList<PageView.Attachment>();
                for (JiraRestClient.Attachment attachment : issue.attachments) {
                    attachments.add(new PageView.Attachment(attachment.id, issue.key, attachment.fileName,
                            attachment.mediaType, "", attachment.size, attachment.content == null ? "" : attachment.content,
                            base + "browse/" + issue.key, EnumSet.noneOf(PageView.Preview.class)));
                }
                return new PageView(view.html, base, images, attachments);
            }
        });
    }

    /** Ein Bild der Lese-Ansicht über dieselbe Anmeldung; nur Adressen unter der Basis-URL dieser Jira. */
    @Override
    public byte[] loadViewImage(final String requested) throws KnowledgeSourceException {
        final URI target = absolute(requested);
        return withSession(new Operation<byte[]>() {
            @Override
            public byte[] run(Session session) throws KnowledgeSourceException {
                return client.download(session, target, READER_MAX_BYTES);
            }
        });
    }

    /** Die Originaldatei eines Anhangs über den Download-Pfad ({@code content}) mit der Anmeldung der Quelle. */
    @Override
    public byte[] loadAttachment(PageView.Attachment attachment) throws KnowledgeSourceException {
        if (attachment == null || attachment.downloadPath().isEmpty()) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Anhang ohne Download-Adresse");
        }
        final URI target = absolute(attachment.downloadPath());
        final boolean html = attachment.mediaType() != null
                && attachment.mediaType().toLowerCase(Locale.ROOT).contains("html");
        return withSession(new Operation<byte[]>() {
            @Override
            public byte[] run(Session session) throws KnowledgeSourceException {
                return client.download(session, target, READER_MAX_BYTES, html);
            }
        });
    }

    private URI absolute(String address) throws KnowledgeSourceException {
        if (address == null || address.trim().isEmpty()) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Leere Adresse");
        }
        try {
            URI target = new URI(address.trim());
            if (!target.isAbsolute()) {
                target = URI.create(base()).resolve(target);
            }
            if (!client.underBase(target)) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Adresse außerhalb dieser Jira: "
                        + target.getHost());
            }
            return target;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Adresse nicht lesbar");
        }
    }

    // --- AddressableKnowledgeSource -----------------------------------------------------------------------------

    /** Eine vollständige HTTP(S)-Adresse oder ein Wert mit dem Schema der Quelle ({@code jira://<locator>/…}). */
    @Override
    public Pattern addressPattern() {
        return addressPattern0();
    }

    static Pattern addressPattern0() {
        return Pattern.compile("^(?:https?|" + JiraIds.SCHEME + ")://[^\\s]+$", Pattern.CASE_INSENSITIVE);
    }

    /**
     * Die Ressourcen-ID zu einer Vorgangsadresse dieser Jira: {@code …/browse/KEY-1}, {@code …/projects/X/issues/KEY-1},
     * {@code …?selectedIssue=KEY-1} oder {@code jira://<locator>/issue/KEY-1}; ohne Netzzugriff. Fremde Adressen sind
     * {@code NOT_FOUND}.
     */
    @Override
    public KnowledgeResourceId resolveAddress(String address) throws KnowledgeSourceException {
        String key = issueKeyOf(address);
        if (key == null) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Kein Vorgang dieser Jira");
        }
        return JiraIds.issue(locator, key);
    }

    String issueKeyOf(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        String value = address.trim();
        String bookmark = JiraIds.SCHEME + "://" + locator + "/";
        if (value.regionMatches(true, 0, bookmark, 0, bookmark.length())) {
            String rest = value.substring(bookmark.length());
            if (rest.startsWith("issue/")) {
                rest = rest.substring("issue/".length());
            } else if (rest.startsWith("browse/")) {
                rest = rest.substring("browse/".length());
            }
            int end = rest.indexOf('/');
            String key = (end < 0 ? rest : rest.substring(0, end)).toUpperCase(Locale.ROOT);
            return JiraIds.isIssueKey(key) ? key : null;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!client.underBase(uri)) {
            return null;
        }
        String basePath = config.baseUrl().getRawPath() == null ? "" : config.baseUrl().getRawPath();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().substring(basePath.length());
        Matcher browse = BROWSE.matcher(path);
        if (browse.matches()) {
            return browse.group(1);
        }
        Matcher project = PROJECT_ISSUE.matcher(path);
        if (project.matches()) {
            return project.group(1);
        }
        Matcher portal = PORTAL_ISSUE.matcher(path);
        if (portal.matches()) {
            return portal.group(1);
        }
        if (uri.getRawQuery() != null) {
            Matcher selected = SELECTED_ISSUE.matcher(uri.getRawQuery());
            if (selected.find()) {
                return selected.group(1);
            }
        }
        return null;
    }

    // --- PartitionListingSource ---------------------------------------------------------------------------------

    @Override
    public PartitionResources resources(final String partitionKey, String cursor, final int limit)
            throws KnowledgeSourceException {
        if (!JiraIds.isProjectKey(partitionKey)) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Kein Projekt dieser Quelle: " + partitionKey);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        final boolean first = cursor == null || cursor.isEmpty();
        final int start = first ? 0 : partitionCursorStart(partitionKey, cursor);
        final int batchSize = Math.min(limit, MAX_PARTITION_BATCH);
        SearchResult result = withSession(new Operation<SearchResult>() {
            @Override
            public SearchResult run(Session session) throws KnowledgeSourceException {
                if (first && client.project(session, partitionKey) == null) {
                    throw new KnowledgeSourceException(Kind.NOT_FOUND, "Projekt nicht gefunden: " + partitionKey);
                }
                return client.search(session, projectJql(partitionKey), start, batchSize, JiraRestClient.LIST_FIELDS);
            }
        });
        List<KnowledgeResource> resources = new ArrayList<KnowledgeResource>();
        for (Issue issue : result.issues) {
            resources.add(resource(issue));
        }
        return new PartitionResources(resources,
                result.nextStart < 0 ? "" : PARTITION_CURSOR_PREFIX + partitionKey + "." + result.nextStart);
    }

    private String projectJql(String projectKey) {
        return withFilter("project = " + jqlString(projectKey)) + " ORDER BY key ASC";
    }

    private static int partitionCursorStart(String partitionKey, String cursor) throws KnowledgeSourceException {
        Matcher matcher = PARTITION_CURSOR.matcher(cursor);
        if (!matcher.matches() || !matcher.group(1).equals(partitionKey)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Cursor gehört nicht zu Projekt " + partitionKey);
        }
        return Integer.parseInt(matcher.group(2));
    }

    // --- ResourceMetadataSource, StoredContentSource, RestQuerySource -------------------------------------------

    @Override
    public String localId(KnowledgeResourceId resourceId) {
        return JiraIds.issueKey(locator, resourceId);
    }

    @Override
    public KnowledgeResource resourceMetadata(final String localId) throws KnowledgeSourceException {
        if (!JiraIds.isIssueKey(localId)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Kein Vorgangsschlüssel dieser Quelle: " + localId);
        }
        return withSession(new Operation<KnowledgeResource>() {
            @Override
            public KnowledgeResource run(Session session) throws KnowledgeSourceException {
                return resource(client.issue(session, localId));
            }
        });
    }

    /** Die unveränderte JSON-Antwort eines Vorgangs ({@code /issue/<KEY>?expand=renderedFields}) samt Metadaten daraus. */
    @Override
    public StoredContent storedContent(final String localId) throws KnowledgeSourceException {
        if (!JiraIds.isIssueKey(localId)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Kein Vorgangsschlüssel dieser Quelle: " + localId);
        }
        return withSession(new Operation<StoredContent>() {
            @Override
            public StoredContent run(Session session) throws KnowledgeSourceException {
                Issue issue = client.issue(session, localId);
                return new StoredContent(resource(issue), issue.raw.getBytes(StandardCharsets.UTF_8),
                        "application/json", SNAPSHOT_FORMAT, Instant.now());
            }
        });
    }

    /** Eine GET-Abfrage unter {@code /rest/} der Quelle mit ihrer Anmeldung; die Antwort kommt unverändert. */
    @Override
    public RestResponse restGet(final String pathAndQuery) throws KnowledgeSourceException {
        return withSession(new Operation<RestResponse>() {
            @Override
            public RestResponse run(Session session) throws KnowledgeSourceException {
                return client.restGet(session, pathAndQuery);
            }
        });
    }

    @Override
    public String toString() {
        return "JiraKnowledgeSource[" + sourceId + ", " + config + "]";
    }

    // --- discover -----------------------------------------------------------------------------------------------

    private List<KnowledgeResource> crawl(Session session, SourceScope scope) throws KnowledgeSourceException {
        Map<KnowledgeResourceId, KnowledgeResource> found = new LinkedHashMap<KnowledgeResourceId, KnowledgeResource>();
        for (String raw : scope.startPoints()) {
            if (found.size() >= scope.maxResources()) {
                break;
            }
            JiraIds.StartPoint point = JiraIds.startPoint(locator, raw);
            if (point == null) {
                LOG.fine("[Jira] Startpunkt ohne bekannte Form übersprungen");
                continue;
            }
            if (point.allProjects) {
                for (Project project : client.projects(session)) {
                    if (!excluded(scope, project.key)) {
                        list(session, projectJql(project.key), found, scope);
                    }
                }
            } else if (point.projectKey != null) {
                if (!excluded(scope, point.projectKey)) {
                    list(session, projectJql(point.projectKey), found, scope);
                }
            } else if (point.jql != null) {
                list(session, withFilter("(" + point.jql + ")" + exclusionJql(scope)) + " ORDER BY key ASC", found, scope);
            } else {
                follow(session, point.issueKey, scope, found);
            }
        }
        return new ArrayList<KnowledgeResource>(found.values());
    }

    private static boolean excluded(SourceScope scope, String projectKey) {
        return scope.selectionApplies() && scope.excludedPartitions().contains(projectKey);
    }

    /** {@code AND project not in (…)} für die abgehakten Projekte, oder leer. */
    private static String exclusionJql(SourceScope scope) {
        List<String> excluded = scope.selectionApplies() ? projectKeys(scope.excludedPartitions())
                : Collections.<String>emptyList();
        return excluded.isEmpty() ? "" : " AND project not in (" + String.join(", ", excluded) + ")";
    }

    /**
     * Alle Vorgänge einer JQL-Abfrage, stapelweise, bis {@code scope.maxResources()} Ressourcen gefunden sind;
     * Vorgänge abgehakter Projekte werden übersprungen.
     */
    private void list(Session session, String jql, Map<KnowledgeResourceId, KnowledgeResource> found, SourceScope scope)
            throws KnowledgeSourceException {
        int max = scope.maxResources();
        int start = 0;
        while (found.size() < max) {
            SearchResult result = client.search(session, jql, start, Math.min(config.pageSize(), max - found.size()),
                    JiraRestClient.LIST_FIELDS);
            for (Issue issue : result.issues) {
                if (found.size() >= max) {
                    break;
                }
                if (excluded(scope, issue.projectKey)) {
                    continue;
                }
                KnowledgeResource resource = resource(issue);
                found.put(resource.id(), resource);
            }
            if (result.nextStart < 0) {
                break;
            }
            start = result.nextStart;
        }
    }

    /** Breitensuche über Verknüpfungen ab einem Vorgang bis {@code maxDepth}; ein unbekannter Startpunkt wird übersprungen. */
    private void follow(Session session, String startKey, SourceScope scope,
                        Map<KnowledgeResourceId, KnowledgeResource> found) throws KnowledgeSourceException {
        Deque<Node> queue = new ArrayDeque<Node>();
        Set<String> seen = new LinkedHashSet<String>();
        queue.add(new Node(startKey, 0));
        seen.add(startKey);
        while (!queue.isEmpty() && found.size() < scope.maxResources()) {
            Node node = queue.poll();
            Issue issue;
            try {
                issue = client.issue(session, node.key);
            } catch (KnowledgeSourceException e) {
                if (e.kind() == Kind.NOT_FOUND || e.kind() == Kind.ACCESS_DENIED && node.depth > 0) {
                    LOG.fine("[Jira] Vorgang übersprungen (" + e.kind() + ")");
                    continue;
                }
                throw e;
            }
            if (excluded(scope, issue.projectKey)) {
                LOG.fine("[Jira] Vorgang eines abgehakten Projekts übersprungen");
                continue;
            }
            KnowledgeResource resource = resource(issue);
            found.put(resource.id(), resource);
            if (node.depth >= scope.maxDepth()) {
                continue;
            }
            for (Link link : issue.links) {
                if (seen.add(link.key)) {
                    queue.add(new Node(link.key, node.depth + 1));
                }
            }
        }
    }

    // --- Hilfen -------------------------------------------------------------------------------------------------

    /** Der Vorgang samt aller Kommentare (bis {@link JiraConfig#maxComments()}), nachgeladen, wenn Jira sie kappte. */
    private Issue fullIssue(Session session, String key) throws KnowledgeSourceException {
        Issue issue = client.issue(session, key);
        int wanted = Math.min(issue.commentTotal, config.maxComments());
        if (issue.comments.size() < wanted) {
            List<JiraRestClient.Comment> all = client.comments(session, key, config.maxComments());
            issue.comments.clear();
            issue.comments.addAll(all);
        } else if (issue.comments.size() > config.maxComments()) {
            issue.comments.subList(config.maxComments(), issue.comments.size()).clear();
        }
        return issue;
    }

    /** Der Vorgangsschlüssel einer ID dieser Quelle; fremde Schemata sind {@code UNSUPPORTED}, anderes {@code NOT_FOUND}. */
    private String key(KnowledgeResourceId id) throws KnowledgeSourceException {
        if (id == null || !JiraIds.SCHEME.equals(id.scheme())) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Keine Ressource dieser Quelle: " + id);
        }
        String key = JiraIds.issueKey(locator, id);
        if (key == null) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Kein Vorgang dieser Jira: " + id.value());
        }
        return key;
    }

    private KnowledgeResource resource(Issue issue) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("jira.key", issue.key);
        put(metadata, "jira.id", issue.id);
        put(metadata, "jira.project", issue.projectKey);
        put(metadata, "jira.issueType", issue.issueType);
        put(metadata, "jira.status", issue.status);
        put(metadata, "jira.priority", issue.priority);
        put(metadata, "jira.resolution", issue.resolution);
        put(metadata, "jira.assignee", issue.assignee);
        put(metadata, "jira.reporter", issue.reporter);
        put(metadata, "jira.createdAt", issue.created == null ? null : issue.created.toString());
        put(metadata, "jira.createdBy", issue.reporter);
        put(metadata, "jira.resolvedAt", issue.resolved == null ? null : issue.resolved.toString());
        put(metadata, "jira.labels", JiraIssueText.join(issue.labels));
        put(metadata, "jira.components", JiraIssueText.join(issue.components));
        put(metadata, "jira.fixVersions", JiraIssueText.join(issue.fixVersions));
        put(metadata, "jira.parent", issue.parentKey);
        return KnowledgeResource.builder(JiraIds.issue(locator, issue.key), sourceId)
                .title(issue.title())
                .contentType(ISSUE_CONTENT_TYPE)
                .revision(revision(issue))
                .parentId(issue.parentKey == null || !JiraIds.isIssueKey(issue.parentKey) ? null
                        : JiraIds.issue(locator, issue.parentKey))
                .scope(issue.projectKey == null ? "" : issue.projectKey)
                .location(browse(issue.key))
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private static void put(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            metadata.put(key, value.trim());
        }
    }

    private static KnowledgeRevision revision(Issue issue) {
        return issue.updated == null ? KnowledgeRevision.unknown()
                : KnowledgeRevision.of(issue.updated, issue.updated.toString());
    }

    private String base() {
        String base = config.baseUrl().toString();
        return base.endsWith("/") ? base : base + "/";
    }

    // --- JiraIssueOperations (Jira-Werkzeuge) -------------------------------------------------------------------

    @Override
    public URI baseUrl() {
        return config.baseUrl();
    }

    @Override
    public String issueKey(String keyOrAddress) {
        if (keyOrAddress == null) {
            return null;
        }
        String value = keyOrAddress.trim();
        String upper = value.toUpperCase(Locale.ROOT);
        if (JiraIds.isIssueKey(upper)) {
            return upper;
        }
        String prefix = JiraIds.SCHEME + ":" + locator + "/issue/";
        if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String key = value.substring(prefix.length()).toUpperCase(Locale.ROOT);
            return JiraIds.isIssueKey(key) ? key : null;
        }
        return issueKeyOf(value);
    }

    @Override
    public JiraIssueOperations.IssueSearch searchIssues(final String jql, final int limit)
            throws KnowledgeSourceException {
        if (jql == null || jql.trim().isEmpty()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "JQL fehlt");
        }
        final int max = Math.max(1, Math.min(100, limit));
        return withSession(new Operation<JiraIssueOperations.IssueSearch>() {
            @Override
            public JiraIssueOperations.IssueSearch run(Session session) throws KnowledgeSourceException {
                SearchResult result = client.search(session, restricted(jql.trim()), 0, max,
                        JiraRestClient.LIST_FIELDS);
                List<IssueSummary> issues = new ArrayList<IssueSummary>();
                for (Issue issue : result.issues) {
                    issues.add(summary(issue));
                }
                return new JiraIssueOperations.IssueSearch(issues, result.total);
            }
        });
    }

    @Override
    public IssueSummary issueSummary(final String issueKey) throws KnowledgeSourceException {
        final String key = requireKey(issueKey);
        return withSession(new Operation<IssueSummary>() {
            @Override
            public IssueSummary run(Session session) throws KnowledgeSourceException {
                // Die Suche nach dem Schlüssel liefert die Listenfelder ohne Beschreibung und Kommentare.
                SearchResult result = client.search(session, "key = " + key, 0, 1, JiraRestClient.LIST_FIELDS);
                for (Issue issue : result.issues) {
                    if (key.equals(issue.key)) {
                        return summary(issue);
                    }
                }
                throw new KnowledgeSourceException(Kind.NOT_FOUND, "Vorgang " + key + " nicht gefunden");
            }
        });
    }

    @Override
    public List<Transition> transitions(final String issueKey) throws KnowledgeSourceException {
        final String key = requireKey(issueKey);
        return withSession(new Operation<List<Transition>>() {
            @Override
            public List<Transition> run(Session session) throws KnowledgeSourceException {
                return client.transitions(session, key);
            }
        });
    }

    @Override
    public String addComment(final String issueKey, final String body) throws KnowledgeSourceException {
        final String key = requireKey(issueKey);
        if (body == null || body.trim().isEmpty()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Kommentar ist leer");
        }
        return withSession(new Operation<String>() {
            @Override
            public String run(Session session) throws KnowledgeSourceException {
                return client.addComment(session, key, body.trim());
            }
        });
    }

    @Override
    public void transition(final String issueKey, final String transitionId) throws KnowledgeSourceException {
        final String key = requireKey(issueKey);
        if (transitionId == null || !transitionId.trim().matches("[0-9]{1,9}")) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Übergangs-ID fehlt oder ist keine Zahl");
        }
        withSession(new Operation<Void>() {
            @Override
            public Void run(Session session) throws KnowledgeSourceException {
                client.transition(session, key, transitionId.trim());
                return null;
            }
        });
    }

    private static String requireKey(String issueKey) throws KnowledgeSourceException {
        String key = issueKey == null ? "" : issueKey.trim().toUpperCase(Locale.ROOT);
        if (!JiraIds.isIssueKey(key)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "kein Vorgangs-Schlüssel (Form ABC-123)");
        }
        return key;
    }

    private IssueSummary summary(Issue issue) {
        return new IssueSummary(issue.key, issue.summary, issue.issueType, issue.status, issue.assignee,
                issue.updated, browse(issue.key));
    }

    private URI browse(String key) {
        return URI.create(base() + "browse/" + key);
    }

    private <T> T withSession(final Operation<T> operation) throws KnowledgeSourceException {
        final SecretRef ref = config.credentialRef();
        if (ref == null) {
            return operation.run(new Session(null));
        }
        try {
            if (!options.sendsLogin()) {
                return operation.run(new Session(null)); // MM_RequiresLogin=false: nur Zertifikat
            }
            return secrets.withSecret(ref, material -> operation.run(new Session(JiraAuthorization.header(material))));
        } catch (SecretUnavailableException e) {
            Kind kind = e.reason() == SecretUnavailableException.Reason.NOT_AVAILABLE ? Kind.UNAVAILABLE
                    : Kind.ACCESS_DENIED;
            throw new KnowledgeSourceException(kind,
                    "Anmeldedaten für Jira-Quelle " + sourceId + " nicht verfügbar (" + e.reason() + ", " + ref
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
        }
    }

    private interface Operation<T> {
        T run(Session session) throws KnowledgeSourceException;
    }

    private static final class Node {
        final String key;
        final int depth;

        Node(String key, int depth) {
            this.key = key;
            this.depth = depth;
        }
    }
}
