package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.AddressableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.AsyncStoredContentSource;
import com.aresstack.enterpriseai.source.api.AttachmentListSource;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.OneTimeCodeChallenge;
import com.aresstack.enterpriseai.source.api.OneTimeCodeRequiredException;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.ChangeListingSource;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.RateLimitedException;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import com.aresstack.enterpriseai.source.api.StoredAttachmentList;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.AttachmentPreview;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
import com.aresstack.spaceharvester.application.sources.ChangeEvent;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import com.aresstack.spaceharvester.source.confluence.ConfluenceWebhooks;
import com.aresstack.spaceharvester.source.confluence.InFlightLimit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link HarvestSource} über einen {@link KnowledgeSourcePort} aus {@code enterprise-ai-sources}.
 *
 * <p>Quellen ohne {@link PartitionListingSource} (MediaWiki, Git, FTP … im Enterprise AI Client) bildet sie mit einem
 * Umfang ({@link SourceScope}) wie die Indexierung dort ab (#187): eine Partition je Quelle (Schlüssel = Quellen-ID),
 * deren Seiten {@link KnowledgeSourcePort#discover} im Umfang liefert und {@link KnowledgeSourcePort#load} liest. Die
 * Seiten-ID ist die Ressourcen-ID als Base64url, der Inhalt der Lesetext des Adapters ({@link KnowledgeDocument#text()},
 * {@link KnowledgeDocumentTextProjector#NATIVE_FORMAT}). Bereiche des Ports gibt es auf diesem Weg nicht.
 */
final class KnowledgeSourceHarvestSource implements HarvestSource {

    /** Schema der öffentlichen Ressourcen-ID von Dateiquellen. */
    static final String FILES_SCHEME = "files";

    /** Schlüssel der Wurzel einer Partition im Enterprise AI Client ({@code space:DEV}). */
    private static final String SPACE_NODE = "space:";
    /** {@link PageAttachments#nativeFormat()} der Ersatzliste aus dem Lesebereich; sie hat keine Rohantworten. */
    static final String READER_ATTACHMENTS = "knowledge-reader-attachments";
    private static final System.Logger LOG = System.getLogger(KnowledgeSourceHarvestSource.class.getName());
    /** Präfix des Cursors der Kindseiten; versioniert das Format. */
    private static final String CURSOR_PREFIX = "c1.";
    /** Präfix des Cursors der Discovery-Listung; versioniert das Format. */
    private static final String DISCOVERY_CURSOR = "d1.";
    /** {@code d1.<Versatz>.<Fingerabdruck der Discovery>}. */
    private static final Pattern DISCOVERY_CURSOR_FORMAT = Pattern.compile("d1\\.(\\d{1,9})\\.([0-9a-f]{16})");
    /** So viele Treffer der Wortsuche prüft eine Titelsuche höchstens auf ihren Titel. */
    static final int TITLE_SEARCH_DEPTH = 1000;
    /** Stapel, mit dem die Titelsuche Partitionen ohne eigene Suche durchgeht. */
    private static final int TITLE_SCAN_BATCH = 500;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KnowledgeSourceHarvestSource.class);

    private final String id;
    private final String type;
    private final String title;
    private final KnowledgeSourcePort port;
    private final InFlightLimit inFlight;
    /** Umfang für {@code discover}, nur ohne {@link PartitionListingSource}; sonst {@code null}. */
    private final SourceScope scope;
    /**
     * Umfang aus Startpunkten und Tiefe für einen Port mit {@link PartitionListingSource} (Confluence), sonst
     * {@code null}: Die Auflistung einer Partition behält nur Seiten, die {@code discover} in diesem Umfang findet.
     */
    private final SourceScope restriction;
    /** Seiten-IDs der letzten vollständigen Discovery im {@link #restriction}; {@code null} ohne eine. */
    private volatile Set<String> inScope;
    /** Die letzte Discovery; Folgestapel derselben Listung lesen aus ihr, statt die Quelle erneut zu befragen. */
    private volatile Discovery discovery;

    KnowledgeSourceHarvestSource(String id, String type, String title, KnowledgeSourcePort port) {
        this(id, type, title, port, null);
    }

    /** @param inFlight Grenze gleichzeitiger asynchroner Abrufe; {@code null} = unbegrenzt */
    KnowledgeSourceHarvestSource(String id, String type, String title, KnowledgeSourcePort port,
                                 InFlightLimit inFlight) {
        this(id, type, title, port, inFlight, null);
    }

    /**
     * @param scope Umfang für {@code discover} (#187); für einen Port ohne {@link PartitionListingSource} der Weg zu
     *              seinen Seiten, für einen mit eigener Auflistung (Confluence) deren Einschränkung auf Startpunkte und
     *              Tiefe; {@code null} ohne diesen Weg bzw. ohne Einschränkung
     */
    KnowledgeSourceHarvestSource(String id, String type, String title, KnowledgeSourcePort port,
                                 InFlightLimit inFlight, SourceScope scope) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.port = port;
        this.inFlight = inFlight;
        this.scope = port instanceof PartitionListingSource ? null : scope;
        this.restriction = port instanceof PartitionListingSource ? scope : null;
    }

    /** Ob die Seiten über {@code discover} und {@code load} kommen statt über eine Seitenliste je Partition. */
    private boolean discovering() {
        return scope != null;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String title() {
        return title;
    }

    /** Lokale Dateien: Eine fehlende Datei in einem lesbaren Verzeichnis ist gelöscht (ADR 0014). */
    @Override
    public boolean confirmsDeletion() {
        return LocalFilesSourceAdapter.TYPE.equals(type);
    }

    @Override
    public Set<SourceCapability> capabilities() {
        Set<SourceCapability> capabilities = EnumSet.of(SourceCapability.PARTITIONS);
        if (port instanceof PartitionListingSource) {
            capabilities.add(SourceCapability.PAGE_LISTING);
        }
        if (port instanceof ChangeListingSource) {
            capabilities.add(SourceCapability.CHANGES);
        }
        if (port instanceof ResourceMetadataSource) {
            capabilities.add(SourceCapability.PAGE_METADATA);
        }
        if (port instanceof StoredContentSource) {
            capabilities.add(SourceCapability.STORED_PAGE);
        }
        if (port instanceof AsyncStoredContentSource) {
            capabilities.add(SourceCapability.ASYNC_STORED_PAGE);
        }
        if (port instanceof AttachmentListSource) {
            capabilities.add(SourceCapability.PAGE_ATTACHMENTS);
        }
        if (port instanceof PageViewSource && port instanceof AttachmentListSource) {
            // Vorschauen gibt es bei Quellen mit Lese-Ansicht und Anhangsliste (Confluence, #183).
            capabilities.add(SourceCapability.ATTACHMENT_PREVIEWS);
        }
        if (port instanceof PageNavigationSource && port instanceof ResourceMetadataSource) {
            capabilities.add(SourceCapability.NAVIGATION);
        }
        if (port instanceof SearchableKnowledgeSource) {
            capabilities.add(SourceCapability.LIVE_SEARCH);
        }
        if (port instanceof SearchableKnowledgeSource || port instanceof PartitionListingSource) {
            capabilities.add(SourceCapability.TITLE_SEARCH);
        }
        if (port instanceof AddressableKnowledgeSource || port instanceof ResourceMetadataSource
                || LocalFilesSourceAdapter.TYPE.equals(type)) {
            capabilities.add(SourceCapability.ADDRESSES);
        }
        if (discovering()) {
            capabilities.addAll(EnumSet.of(SourceCapability.PAGE_LISTING, SourceCapability.PAGE_METADATA,
                    SourceCapability.STORED_PAGE, SourceCapability.TITLE_SEARCH, SourceCapability.ADDRESSES));
        }
        return capabilities;
    }

    @Override
    public List<SourceSearchMatch> searchPages(String text, int limit) {
        return searchPages(text, limit, SearchSpaces.all());
    }

    /**
     * Der Space-Filter geht in die Anfrage der Quelle: eine Auswahl als {@code space in (…)}
     * ({@link SourceQuery#withIncludedPartitions}), ein Ausschluss wie im Enterprise AI Client als
     * {@code space not in (…)} ({@link SourceQuery#withExcludedPartitions}); beides ohne Obergrenze der Spaces.
     */
    @Override
    public List<SourceSearchMatch> searchPages(String text, int limit, SearchSpaces spaces) {
        if (!(port instanceof SearchableKnowledgeSource searchable)) {
            return HarvestSource.super.searchPages(text, limit);
        }
        List<SourceSearchHit> hits;
        try {
            hits = searchable.search(new SourceQuery(text, limit).withIncludedPartitions(spaces.only())
                    .withExcludedPartitions(spaces.excluded()));
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        Map<String, SourceSearchMatch> matches = new LinkedHashMap<>();
        for (SourceSearchHit hit : hits) {
            String version = hit.revision().version();
            matches.putIfAbsent(nativeId(hit.resourceId()), new SourceSearchMatch(nativeId(hit.resourceId()),
                    hit.title(), hit.snippet(), hit.url(), version == null || version.isEmpty() ? null : version,
                    hit.revision().modifiedAt().orElse(null)));
        }
        return List.copyOf(matches.values());
    }

    /**
     * Mit eigener Suche (Confluence) über die Wortsuche der Quelle, dieselbe CQL wie {@link #searchPages} mit
     * demselben Escaping und Space-Filter ({@code siteSearch ~ "…" AND type=page}); der Enterprise AI Client kennt keine
     * reine Titelsuche. Von bis zu {@value #TITLE_SEARCH_DEPTH} Treffern in Rangfolge der Quelle bleiben die, deren Titel
     * den Text enthält; Confluence gewichtet Titel hoch, eine Seite, deren Titel nur ein Wortteil enthält, findet die
     * Wortsuche aber nicht. Ohne eigene Suche (lokale Dateien) gehen die Partitionen des Filters Stapel für Stapel durch
     * ihre Auflistung; der Titel ist dort der Dateiname. Treffer der Wortsuche kennen ihren Space nicht.
     */
    @Override
    public List<PageNode> findByTitle(String title, SearchSpaces spaces, int limit) {
        String term = title == null ? "" : title.trim();
        if (term.isEmpty() || limit < 1) {
            return List.of();
        }
        String needle = term.toLowerCase(Locale.ROOT);
        try {
            if (port instanceof SearchableKnowledgeSource searchable) {
                int depth = Math.min(Math.max(limit * 4, 100), Math.max(limit, TITLE_SEARCH_DEPTH));
                Map<String, PageNode> found = new LinkedHashMap<>();
                for (SourceSearchHit hit : searchable.search(new SourceQuery(term, depth)
                        .withIncludedPartitions(spaces.only()).withExcludedPartitions(spaces.excluded()))) {
                    if (found.size() < limit && contains(hit.title(), needle)) {
                        found.putIfAbsent(nativeId(hit.resourceId()),
                                new PageNode(nativeId(hit.resourceId()), hit.title(), null, null));
                    }
                }
                return List.copyOf(found.values());
            }
            if (discovering()) {
                return titlesOfDiscovery(needle, spaces, limit);
            }
            if (port instanceof PartitionListingSource listing) {
                List<PageNode> found = new ArrayList<>();
                for (SourcePartition partition : port.partitions()) {
                    String cursor = "";
                    while (found.size() < limit && spaces.permits(partition.key())) {
                        PartitionResources batch = listing.resources(partition.key(), cursor, TITLE_SCAN_BATCH);
                        for (KnowledgeResource resource : batch.resources()) {
                            PageMetadata metadata = pageMetadata(resource, partition.key());
                            if (found.size() < limit && contains(metadata.title(), needle)) {
                                found.add(new PageNode(metadata.pageId(), metadata.title(), metadata.partitionKey(),
                                        null));
                            }
                        }
                        if (batch.complete()) {
                            break;
                        }
                        cursor = batch.nextCursor();
                    }
                }
                return List.copyOf(found);
            }
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return List.of();
    }

    /** Ohne eigene Suche: die Seiten der Discovery, der Titel ist der des Adapters. */
    private List<PageNode> titlesOfDiscovery(String needle, SearchSpaces spaces, int limit) {
        List<PageNode> found = new ArrayList<>();
        String cursor = null;
        while (found.size() < limit && spaces.permits(id)) {
            PageMetadataBatch batch = pageMetadata(id, cursor, TITLE_SCAN_BATCH);
            for (PageMetadata metadata : batch.pages()) {
                if (found.size() < limit && contains(metadata.title(), needle)) {
                    found.add(new PageNode(metadata.pageId(), metadata.title(), metadata.partitionKey(), null));
                }
            }
            cursor = batch.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        return List.copyOf(found);
    }

    private static boolean contains(String title, String needle) {
        return title != null && title.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public boolean addressable(String address) {
        String value = address == null ? "" : address.trim();
        return !value.isEmpty() && (byResourceId(value).isPresent() || pattern(value) != null);
    }

    /**
     * Zuerst Ressourcen-IDs und Bookmarks der Quelle ({@code confluence:<Ort>/page/123},
     * {@code confluence://<Ort>/page/123}, bei Dateiquellen {@code files:<Quelle>/<Seiten-ID>}), dann die
     * Adressauflösung der Quelle selbst ({@link AddressableKnowledgeSource}, im Enterprise AI Client dieselbe).
     * Unbekannte und für das Konto gesperrte Seiten sind leer, nicht ein Fehler.
     */
    @Override
    public Optional<String> resolveAddress(String address) {
        String value = address == null ? "" : address.trim();
        Optional<String> byId = byResourceId(value);
        if (byId.isPresent()) {
            return byId;
        }
        AddressableKnowledgeSource addressable = pattern(value);
        if (addressable == null) {
            return Optional.empty();
        }
        KnowledgeResourceId resolved;
        try {
            resolved = addressable.resolveAddress(value);
        } catch (KnowledgeSourceException e) {
            if (e.kind() == KnowledgeSourceException.Kind.NOT_FOUND
                    || e.kind() == KnowledgeSourceException.Kind.ACCESS_DENIED) {
                return Optional.empty();
            }
            throw accessFailure(e);
        }
        return resolved == null ? Optional.empty() : Optional.of(nativeId(resolved));
    }

    /** Die Quelle, wenn ihr Adressmuster auf die ganze Eingabe passt; sonst {@code null}. */
    private AddressableKnowledgeSource pattern(String value) {
        if (value.isEmpty() || !(port instanceof AddressableKnowledgeSource addressable)) {
            return null;
        }
        Pattern pattern = addressable.addressPattern();
        return pattern != null && pattern.matcher(value).matches() ? addressable : null;
    }

    private Optional<String> byResourceId(String value) {
        if (LocalFilesSourceAdapter.TYPE.equals(type)) {
            String prefix = FILES_SCHEME + ":" + id + "/";
            boolean own = value.startsWith(prefix) && value.length() > prefix.length()
                    && value.indexOf('/', prefix.length()) < 0;
            return own ? Optional.of(value.substring(prefix.length())) : Optional.empty();
        }
        if (discovering() && !(port instanceof ResourceMetadataSource)) {
            return ownResource(value).map(this::nativeId);
        }
        if (!(port instanceof ResourceMetadataSource single)) {
            return Optional.empty();
        }
        KnowledgeResourceId resourceId;
        try {
            // Ein Bookmark hat nach dem Schema immer //, eine Ressourcen-ID nie (KnowledgeBookmarkIds).
            int separator = value.indexOf("://");
            resourceId = separator > 0
                    ? KnowledgeResourceId.of(value.substring(0, separator), value.substring(separator + 3))
                    : KnowledgeResourceId.of(value);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String local = single.localId(resourceId);
        return local == null || local.isEmpty() ? Optional.empty() : Optional.of(local);
    }

    /**
     * Eine Ressourcen-ID ({@code wiki:wiki.intern/w/Start}) oder ein Bookmark ({@code wiki://wiki.intern/w/Start})
     * dieser Quelle: Schema und Endpoint der Quellenadresse ({@link KnowledgeSourcePort#sourceId()}), dahinter das
     * Ziel. Ob es die Seite gibt, klärt erst das Laden.
     */
    private Optional<KnowledgeResourceId> ownResource(String value) {
        SourceAddress address = port.sourceId();
        if (address == null || address.scheme().isEmpty()) {
            return Optional.empty();
        }
        String specific = value.startsWith(address.scheme() + "://")
                ? value.substring(address.scheme().length() + 3)
                : value.startsWith(address.scheme() + ":") ? value.substring(address.scheme().length() + 1) : null;
        String locator = address.locator() + "/";
        if (specific == null || !specific.startsWith(locator) || specific.length() == locator.length()) {
            return Optional.empty();
        }
        try {
            return Optional.of(KnowledgeResourceId.of(address.scheme(), specific));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Partition> partitions() {
        if (discovering()) {
            return List.of(new Partition(id, title, null));
        }
        try {
            return port.partitions().stream().map(this::partition).toList();
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    @Override
    public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
        if (discovering()) {
            return discovered(partitionKey, cursor, limit);
        }
        if (!(port instanceof PartitionListingSource listing)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED,
                    "Quelle kann Seiten einer Partition nicht auflisten", null);
        }
        // Mit Startpunkten: Jede Auflistung beginnt mit einer vollständigen Discovery im Umfang, deren Seiten bleiben.
        Set<String> allowed = restriction == null ? null
                : cursor == null || cursor.isEmpty() || inScope == null ? restrict() : inScope;
        PartitionResources batch;
        try {
            batch = listing.resources(partitionKey, cursor == null ? "" : cursor, limit);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return new PageMetadataBatch(batch.resources().stream()
                .map(resource -> pageMetadata(resource, partitionKey))
                .filter(page -> allowed == null || allowed.contains(page.pageId())).toList(), batch.nextCursor());
    }

    /**
     * Die Seiten im Umfang aus Startpunkten und Tiefe, wie die Indexierung im Enterprise AI Client
     * ({@link KnowledgeSourcePort#discover}). Scheitert sie, scheitert die Auflistung, und keine Seite gilt als außerhalb
     * des Umfangs.
     */
    private Set<String> restrict() {
        inScope = null;
        List<KnowledgeResource> found;
        try {
            found = port.discover(restriction);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        Set<String> pageIds = new java.util.HashSet<>();
        for (KnowledgeResource resource : found) {
            if (resource != null) {
                pageIds.add(nativeId(resource.id()));
            }
        }
        if (found.size() >= restriction.maxResources()) {
            // Wie im Client begrenzt maxResources den Umfang; Seiten jenseits davon fallen aus dem Katalog.
            LOG.warn("Discovery der Quelle {} hat die Obergrenze maxResources erreicht ({} Seiten)", id, found.size());
        }
        Set<String> complete = Set.copyOf(pageIds);
        inScope = complete;
        return complete;
    }

    @Override
    public boolean outsideScope(String pageId) {
        Set<String> current = inScope;
        return restriction != null && current != null && !current.contains(pageId);
    }

    /**
     * Ein Stapel der Discovery wie {@code IndexKnowledgeUseCase.indexSource} im Enterprise AI Client: ein Aufruf von
     * {@link KnowledgeSourcePort#discover} im Umfang der Quelle, jede Ressource einmal, in der Reihenfolge der Quelle.
     * Der erste Stapel fragt immer neu; Folgestapel lesen aus derselben Discovery, deren Fingerabdruck im Cursor
     * steht. Ist sie nicht mehr da und liefert die Quelle inzwischen etwas anderes, bricht die Listung ab, statt Seiten
     * zweier Stände zu mischen.
     */
    private PageMetadataBatch discovered(String partitionKey, String cursor, int limit) {
        if (!id.equals(partitionKey)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.NOT_FOUND, "Unbekannte Partition", null);
        }
        int offset = 0;
        String expected = null;
        if (cursor != null && !cursor.isEmpty()) {
            Matcher matcher = DISCOVERY_CURSOR_FORMAT.matcher(cursor);
            if (!matcher.matches()) {
                throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED,
                        "Cursor gehört nicht zu dieser Quelle", null);
            }
            offset = Integer.parseInt(matcher.group(1));
            expected = matcher.group(2);
        }
        Discovery current = discovery;
        if (expected == null || current == null || !current.fingerprint().equals(expected)) {
            current = discover();
            discovery = current;
            if (expected != null && !current.fingerprint().equals(expected)) {
                throw new SourceAccessException(id, SourceAccessException.Reason.UNAVAILABLE,
                        "Die Quelle hat sich während der Listung geändert", null);
            }
        }
        List<KnowledgeResource> resources = current.resources();
        int from = Math.min(offset, resources.size());
        int to = Math.min(resources.size(), from + Math.max(1, limit));
        return new PageMetadataBatch(resources.subList(from, to).stream()
                .map(resource -> pageMetadata(resource, id)).toList(),
                to < resources.size() ? DISCOVERY_CURSOR + to + "." + current.fingerprint() : null);
    }

    private Discovery discover() {
        List<KnowledgeResource> found;
        try {
            found = port.discover(scope);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        Map<KnowledgeResourceId, KnowledgeResource> unique = new LinkedHashMap<>();
        for (KnowledgeResource resource : found) {
            if (resource != null) {
                unique.putIfAbsent(resource.id(), resource);
            }
        }
        MessageDigest digest = sha256();
        for (KnowledgeResource resource : unique.values()) {
            String version = resource.revision().version();
            digest.update((resource.id().value() + '\n' + resource.title() + '\n' + (version == null ? "" : version)
                    + '\n' + resource.revision().modifiedAt().map(Instant::toString).orElse("") + '\n'
                    + resource.parentId().map(KnowledgeResourceId::value).orElse("") + '\u0000')
                    .getBytes(StandardCharsets.UTF_8));
        }
        return new Discovery(HexFormat.of().formatHex(digest.digest()).substring(0, 16),
                List.copyOf(unique.values()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    /**
     * Der aktuelle Stand einer Seite über {@link KnowledgeSourcePort#load}, wie im Enterprise AI Client. Leitet die
     * Quelle weiter (Wiki-Redirect), gilt die angefragte Seite als nicht gefunden: Dort bleibt nur das Ziel im Index,
     * das die Discovery selbst liefert.
     */
    private KnowledgeDocument loaded(String pageId) {
        KnowledgeResourceId requested = resourceIdOf(pageId);
        KnowledgeDocument document;
        try {
            document = port.load(requested);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        if (!document.id().equals(requested)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.NOT_FOUND,
                    "Seite leitet auf eine andere weiter", null);
        }
        return document;
    }

    /** Die Ressourcen-ID hinter einer Seiten-ID der Discovery; nur die kanonische Kodierung gilt. */
    private KnowledgeResourceId resourceIdOf(String pageId) {
        try {
            KnowledgeResourceId resourceId = KnowledgeResourceId.of(new String(Base64.getUrlDecoder().decode(pageId),
                    StandardCharsets.UTF_8));
            if (encodeResourceId(resourceId).equals(pageId)) {
                return resourceId;
            }
        } catch (IllegalArgumentException e) {
            // unten: keine Seiten-ID dieser Quelle
        }
        throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED,
                "Keine Seiten-ID dieser Quelle", null);
    }

    private static String encodeResourceId(KnowledgeResourceId resourceId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                resourceId.value().getBytes(StandardCharsets.UTF_8));
    }

    /** Ergebnis eines {@code discover}-Aufrufs mit Fingerabdruck über IDs und Kopfdaten. */
    private record Discovery(String fingerprint, List<KnowledgeResource> resources) {
    }

    /** Confluence verschickt Webhooks (ADR 0014), wo ein Administrator sie registriert hat. */
    @Override
    public boolean readsChangeEvents() {
        return ConfluenceSourceAdapter.TYPE.equals(type);
    }

    @Override
    public List<ChangeEvent> changeEvents(byte[] body, String eventKey) {
        if (!readsChangeEvents()) {
            return HarvestSource.super.changeEvents(body, eventKey);
        }
        return ConfluenceWebhooks.parse(body, eventKey);
    }

    @Override
    public boolean listsChanges() {
        return port instanceof ChangeListingSource;
    }

    @Override
    public PageMetadataBatch changedPageMetadata(String partitionKey, java.time.Duration within, String cursor,
                                                 int limit) {
        ChangeListingSource changes = changes();
        PartitionResources batch;
        try {
            batch = changes.changedResources(partitionKey, within, cursor == null ? "" : cursor, limit);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return new PageMetadataBatch(batch.resources().stream()
                .map(resource -> pageMetadata(resource, partitionKey)).toList(), batch.nextCursor());
    }

    @Override
    public PageMetadataBatch removedPageMetadata(String partitionKey, String cursor, int limit) {
        ChangeListingSource changes = changes();
        PartitionResources batch;
        try {
            batch = changes.removedResources(partitionKey, cursor == null ? "" : cursor, limit);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return new PageMetadataBatch(batch.resources().stream()
                .map(resource -> pageMetadata(resource, partitionKey)).toList(), batch.nextCursor());
    }

    private ChangeListingSource changes() {
        if (!(port instanceof ChangeListingSource changes)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED,
                    "Quelle meldet keine Änderungen", null);
        }
        return changes;
    }

    @Override
    public PageMetadata pageMetadata(String pageId) {
        if (discovering() && !(port instanceof ResourceMetadataSource)) {
            return pageMetadata(loaded(pageId).resource(), id);
        }
        if (!(port instanceof ResourceMetadataSource single)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED,
                    "Quelle kann Metadaten einzelner Seiten nicht liefern", null);
        }
        KnowledgeResource resource;
        try {
            resource = single.resourceMetadata(pageId);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return withSpace(resource);
    }

    @Override
    public StoredPage storedPage(String pageId) {
        if (discovering() && !(port instanceof StoredContentSource)) {
            // Kein natives Format: abgelegt wird der Lesetext des Adapters, mit den Kopfdaten aus demselben Laden.
            KnowledgeDocument document = loaded(pageId);
            return new StoredPage(pageMetadata(document.resource(), id),
                    document.text().getBytes(StandardCharsets.UTF_8), KnowledgeDocumentTextProjector.MEDIA_TYPE,
                    KnowledgeDocumentTextProjector.NATIVE_FORMAT, Instant.now());
        }
        if (!(port instanceof StoredContentSource stored)) {
            return HarvestSource.super.storedPage(pageId);
        }
        StoredContent content;
        try {
            content = stored.storedContent(pageId);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return storedPage(content);
    }

    @Override
    public boolean asyncStoredPages() {
        return port instanceof AsyncStoredContentSource;
    }

    @Override
    public CompletionStage<StoredPage> storedPageAsync(String pageId) {
        if (!(port instanceof AsyncStoredContentSource stored)) {
            return HarvestSource.super.storedPageAsync(pageId);
        }
        CompletableFuture<StoredContent> request = inFlight == null
                ? stored.storedContentAsync(pageId).toCompletableFuture()
                : inFlight.submit(() -> stored.storedContentAsync(pageId).toCompletableFuture());
        CompletableFuture<StoredPage> result = new CompletableFuture<>();
        request.whenComplete((content, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                result.completeExceptionally(cause instanceof KnowledgeSourceException e ? accessFailure(e) : cause);
                return;
            }
            try {
                result.complete(storedPage(content));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        result.whenComplete((page, error) -> {
            if (result.isCancelled()) {
                request.cancel(true);
            }
        });
        return result;
    }

    private StoredPage storedPage(StoredContent content) {
        return new StoredPage(withSpace(content.resource()), content.payload(), content.mediaType(),
                content.nativeFormat(), content.retrievedAt());
    }

    @Override
    public PageAttachments attachments(String pageId) {
        if (!(port instanceof AttachmentListSource listing)) {
            return HarvestSource.super.attachments(pageId);
        }
        StoredAttachmentList list;
        try {
            list = listing.storedAttachments(pageId);
        } catch (KnowledgeSourceException e) {
            if (e.kind() == KnowledgeSourceException.Kind.INVALID_RESPONSE && port instanceof PageViewSource view) {
                return readerAttachments(pageId, view, e);
            }
            throw accessFailure(e);
        }
        return new PageAttachments(pageId, list.attachments().stream().map(KnowledgeSourceHarvestSource::attachment)
                .toList(), list.payloads(), list.mediaType(), list.nativeFormat(), list.warnings(), list.retrievedAt());
    }

    /**
     * Liefert die Quelle die vollständige Liste nicht (mehr als {@link AttachmentListSource#MAX_ATTACHMENTS} Einträge,
     * wiederholte Seiten), zeigt der Enterprise AI Client im Lesebereich trotzdem die ersten Anhänge
     * ({@code ConfluenceKnowledgeSource.readerAttachments}, höchstens 500). Ebenso hier: die Liste des Lesebereichs, ohne
     * Rohantworten, mit Warnung. Scheitert auch sie, bleibt die Seite ohne Anhänge, wie dort.
     */
    private PageAttachments readerAttachments(String pageId, PageViewSource view, KnowledgeSourceException cause) {
        List<PageAttachment> attachments = List.of();
        String warning = "Anhangsliste nicht vollständig lieferbar (" + cause.kind() + "); es gelten die Anhänge "
                + "des Lesebereichs";
        try {
            attachments = view.attachmentsOf(resource(pageId).id()).stream()
                    .map(KnowledgeSourceHarvestSource::attachment).toList();
        } catch (KnowledgeSourceException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Quelle {0}: Anhänge des Lesebereichs nicht lesbar ({1})", id,
                    e.getClass().getSimpleName());
            warning = "Anhangsliste nicht lieferbar; die Seite bleibt ohne Anhänge";
        }
        return new PageAttachments(pageId, attachments, List.of(), "application/json", READER_ATTACHMENTS,
                List.of(warning), Instant.now());
    }

    @Override
    public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
        if (!(port instanceof PageViewSource files)) {
            return HarvestSource.super.attachmentFile(attachment, maxBytes);
        }
        KnowledgeResourceId resourceId;
        try {
            resourceId = KnowledgeResourceId.of(attachment.resourceId());
        } catch (IllegalArgumentException e) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED, "Keine Ressourcen-ID", null);
        }
        if (!files.isAttachment(resourceId)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED, "Kein Anhang der Quelle",
                    null);
        }
        SourceFile file;
        try {
            // Die Grenze gilt schon beim Lesen im Transport, nicht erst nach dem vollständigen Download.
            file = files.loadAttachmentFile(resourceId, (int) Math.min(maxBytes, Integer.MAX_VALUE));
        } catch (ResponseTooLargeException e) {
            throw new AttachmentTooLargeException(id, e.maxBytes());
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        PageAttachment described = attachment(file.resource());
        if (!described.attachmentId().equals(attachment.attachmentId())) {
            throw new SourceAccessException(id, SourceAccessException.Reason.INVALID_RESPONSE,
                    "Download gehört zu einem anderen Anhang", null);
        }
        return new AttachmentFile(described, file.content(), Instant.now());
    }

    /**
     * Vorschau über {@link PageViewSource#loadAttachmentPreview} (#183), bei Confluence die Dokumentkonvertierung
     * ({@code ConfluenceRestClient#conversionPath}); die Angaben des Anhangs liest der Adapter dabei frisch. Die Grenze
     * der Quelle gilt beim Lesen, die des Aufrufers danach.
     */
    @Override
    public byte[] attachmentPreview(PageAttachment attachment, AttachmentPreview preview, long maxBytes) {
        if (!(port instanceof PageViewSource views) || !(port instanceof AttachmentListSource)) {
            return HarvestSource.super.attachmentPreview(attachment, preview, maxBytes);
        }
        Objects.requireNonNull(preview, "preview");
        KnowledgeResourceId resourceId;
        try {
            resourceId = KnowledgeResourceId.of(attachment.resourceId());
        } catch (IllegalArgumentException e) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED, "Keine Ressourcen-ID", null);
        }
        if (!views.isAttachment(resourceId)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED, "Kein Anhang der Quelle",
                    null);
        }
        PageView.Preview kind = PageView.Preview.valueOf(preview.name());
        PageView.Attachment described = new PageView.Attachment(attachment.attachmentId(), null, attachment.title(),
                attachment.mediaType(), attachment.version(), attachment.fileSize() == null ? -1
                : attachment.fileSize(), null, null, EnumSet.of(kind));
        byte[] bytes;
        try {
            bytes = views.loadAttachmentPreview(described, kind);
        } catch (ResponseTooLargeException e) {
            throw new AttachmentTooLargeException(id, e.maxBytes());
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        if (bytes.length > maxBytes) {
            throw new AttachmentTooLargeException(id, maxBytes);
        }
        return bytes;
    }

    @Override
    public List<ViewImage> viewImages(String pageId) {
        if (!(port instanceof PageViewSource views) || !(port instanceof ResourceMetadataSource single)) {
            return HarvestSource.super.viewImages(pageId);
        }
        PageView view;
        try {
            view = views.pageView(single.resourceMetadata(pageId).id());
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return images(view);
    }

    private static List<ViewImage> images(PageView view) {
        return view.images().stream().map(image -> {
            KnowledgeResource resource = image.resource();
            String version = resource == null ? null : resource.revision().version();
            return new ViewImage(image.src(), image.title(), resource == null ? null : lastSegment(resource.id()),
                    resource == null ? null : resource.contentType(),
                    version == null || version.isEmpty() ? null : version);
        }).toList();
    }

    /**
     * Ansicht ({@link PageViewSource#pageView}, bei Confluence {@code body.export_view}) und Lesetext
     * ({@link KnowledgeSourcePort#load}, bei Confluence {@code body.view}) wie im Enterprise AI Client. Die Version
     * nennt die Ansicht selbst nicht; sie gilt nur, wenn Metadaten vorher und Lesetext danach dieselbe nennen.
     */
    @Override
    public ReadingPage readingPage(String pageId) {
        if (!(port instanceof PageViewSource views) || !(port instanceof ResourceMetadataSource single)) {
            return HarvestSource.super.readingPage(pageId);
        }
        try {
            KnowledgeResource before = single.resourceMetadata(pageId);
            PageView view = views.pageView(before.id());
            KnowledgeDocument document = port.load(before.id());
            String version = before.revision().version();
            boolean same = version != null && !version.isEmpty()
                    && version.equals(document.resource().revision().version());
            return new ReadingPage(same ? version : null, view.html(), view.baseUri(), document.text(), images(view));
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    /**
     * Wie im Enterprise AI Client ({@link PageNavigationSource}): Kinder in Confluence-Reihenfolge mit
     * {@code hasChildren}; der Cursor ist der Folgelink der Quelle, undurchsichtig kodiert und an den Knoten gebunden.
     */
    @Override
    public ChildPages children(String partitionKey, String parentPageId, String cursor) {
        if (!(port instanceof PageNavigationSource navigation)) {
            return HarvestSource.super.children(partitionKey, parentPageId, cursor);
        }
        try {
            NavigationNode parent;
            String partition;
            if (parentPageId == null) {
                partition = Objects.requireNonNull(partitionKey, "partitionKey");
                parent = NavigationNode.root(SPACE_NODE + partition, partition, SPACE_NODE + partition,
                        NavigationNode.Children.UNKNOWN);
            } else {
                KnowledgeResource page = resource(parentPageId);
                partition = withSpace(page).partitionKey();
                parent = NavigationNode.page(page.id(), null, NavigationNode.Children.UNKNOWN);
            }
            NavigationChildren batch = navigation.children(parent, decodeCursor(cursor));
            return new ChildPages(partition, batch.nodes().stream().map(node -> node(node, partition)).toList(),
                    batch.complete() ? null : encodeCursor(batch.nextCursor()));
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    @Override
    public PagePosition position(String pageId) {
        if (!(port instanceof PageNavigationSource navigation)) {
            return HarvestSource.super.position(pageId);
        }
        try {
            com.aresstack.enterpriseai.source.api.PagePosition position = navigation.position(resource(pageId).id());
            String partition = position.root().key().startsWith(SPACE_NODE)
                    ? position.root().key().substring(SPACE_NODE.length()) : position.root().key();
            return new PagePosition(partition, position.root().title(),
                    position.ancestors().stream().map(node -> node(node, partition)).toList(),
                    node(position.page(), partition));
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    @Override
    public PageReferences references(String pageId) {
        if (!(port instanceof PageNavigationSource navigation)) {
            return HarvestSource.super.references(pageId);
        }
        try {
            CrossReferences references = navigation.crossReferences(resource(pageId).id());
            return new PageReferences(links(references.outgoing()),
                    references.incomingSupported() ? links(references.incoming()) : null);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    private KnowledgeResource resource(String pageId) throws KnowledgeSourceException {
        if (!(port instanceof ResourceMetadataSource single)) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED,
                    "Quelle kann Seiten nicht über ihre ID finden");
        }
        return single.resourceMetadata(pageId);
    }

    private PageNode node(NavigationNode node, String partitionKey) {
        Boolean children = switch (node.children()) {
            case YES -> Boolean.TRUE;
            case NO -> Boolean.FALSE;
            case UNKNOWN -> null;
        };
        return new PageNode(nativeId(node.resource()), node.title(), partitionKey, children);
    }

    private List<PageNode> links(List<SourceLink> links) {
        return links.stream().map(link -> new PageNode(nativeId(link.target()), link.label(), null, null)).toList();
    }

    private static String encodeCursor(String next) {
        return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                next.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return "";
        }
        try {
            if (cursor.startsWith(CURSOR_PREFIX)) {
                return new String(Base64.getUrlDecoder().decode(cursor.substring(CURSOR_PREFIX.length())),
                        StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException e) {
            // unten: ungültig
        }
        throw new InvalidQueryException("Cursor gehört nicht zu diesem Knoten");
    }

    @Override
    public byte[] viewImage(ViewImage image, long maxBytes) {
        if (!(port instanceof PageViewSource views)) {
            return HarvestSource.super.viewImage(image, maxBytes);
        }
        byte[] bytes;
        try {
            bytes = views.loadViewImage(image.src());
        } catch (ResponseTooLargeException e) {
            throw new AttachmentTooLargeException(id, e.maxBytes());
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        if (bytes.length > maxBytes) {
            throw new AttachmentTooLargeException(id, maxBytes);
        }
        return bytes;
    }

    private static PageAttachment attachment(KnowledgeResource resource) {
        String size = resource.metadata().asMap().get(KnowledgeMetadata.FILE_SIZE);
        String version = resource.revision().version();
        return new PageAttachment(lastSegment(resource.id()), resource.id().value(), resource.title(),
                resource.contentType(), size == null ? null : Long.valueOf(size),
                version == null || version.isEmpty() ? null : version, resource.revision().modifiedAt().orElse(null),
                resource.location().orElse(null));
    }

    /** Metadaten einer einzeln abgerufenen Seite; ihr Space muss in der Antwort stehen. */
    private PageMetadata withSpace(KnowledgeResource resource) {
        if (discovering()) {
            return pageMetadata(resource, id);
        }
        if (resource.scope() == null || resource.scope().isEmpty()) {
            throw new SourceAccessException(id, SourceAccessException.Reason.INVALID_RESPONSE,
                    "Seite ohne Space in der Antwort der Quelle", null);
        }
        return pageMetadata(resource, resource.scope());
    }

    private PageMetadata pageMetadata(KnowledgeResource resource, String partitionKey) {
        String version = resource.revision().version();
        return new PageMetadata(
                nativeId(resource.id()),
                publicResourceId(resource.id()),
                resource.title(),
                discovering() || resource.scope() == null || resource.scope().isEmpty() ? partitionKey
                        : resource.scope(),
                resource.parentId().map(this::nativeId).orElse(null),
                version == null || version.isEmpty() ? null : version,
                resource.revision().modifiedAt().orElse(null),
                resource.location().orElse(null),
                attributes(resource.metadata().asMap()));
    }

    /**
     * Quellattribute mit stabil sortierten Listen: Labels liefert der Adapter kommagetrennt in der Reihenfolge der
     * Quelle; für {@code attributesHash} werden sie sortiert und entdoppelt (Confluence-Labels enthalten keine Kommas).
     */
    private static SortedMap<String, String> attributes(Map<String, String> source) {
        SortedMap<String, String> attributes = new TreeMap<>(source);
        attributes.replaceAll((key, value) -> key.endsWith(".labels") && value != null
                ? String.join(",", new TreeSet<>(Arrays.stream(value.split(",")).map(String::trim)
                        .filter(label -> !label.isEmpty()).toList()))
                : value);
        return attributes;
    }

    /**
     * Die ID der Seite in der Quelle: die lokale ID des Adapters ({@link ResourceMetadataSource#localId}), über
     * {@code discover} die ganze Ressourcen-ID als Base64url, sonst das letzte Pfadsegment der Ressourcen-ID
     * ({@code confluence:host/page/123} → {@code 123}).
     */
    private String nativeId(KnowledgeResourceId id) {
        if (port instanceof ResourceMetadataSource single) {
            String local = single.localId(id);
            if (local != null && !local.isEmpty()) {
                return local;
            }
        }
        if (discovering()) {
            return encodeResourceId(id);
        }
        return lastSegment(id);
    }

    /** Letzter Pfadteil der Ressourcen-ID, bei Confluence die Content-ID (Seiten und Anhänge). */
    private static String lastSegment(KnowledgeResourceId id) {
        String part = id.schemeSpecificPart();
        return part.substring(part.lastIndexOf('/') + 1);
    }

    private Partition partition(SourcePartition partition) {
        return new Partition(partition.key(), partition.title(),
                partition.homeResource() == null ? null : publicResourceId(partition.homeResource()));
    }

    /**
     * Die Ressourcen-ID, wie sie die API zeigt. Die Dateiquelle bildet ihre ID upstream aus dem Verzeichnis auf dem
     * Server ({@code file:/srv/handbuch/start.md}); nach außen gilt stattdessen {@code files:<Quelle>/<Seiten-ID>}
     * (#90), undurchsichtig und so stabil wie die Seiten-ID (der relative Pfad als Base64url). Zurückgelesen wird die
     * Ressourcen-ID einer Seite nie, aufgelöst wird immer über die Seiten-ID.
     */
    private String publicResourceId(KnowledgeResourceId resourceId) {
        return LocalFilesSourceAdapter.TYPE.equals(type) ? FILES_SCHEME + ":" + id + "/" + nativeId(resourceId)
                : resourceId.value();
    }

    private SourceAccessException accessFailure(KnowledgeSourceException e) {
        if (e instanceof OneTimeCodeRequiredException required) {
            // ADR 0018: die Einmalcode-Abfrage des Adapters geht an den Auftrag, nicht als bloße Verweigerung.
            return new SignInRequiredException(id, e.getMessage(), new Challenge(required.challenge()), e);
        }
        if (e instanceof RateLimitedException) {
            return new SourceAccessException(id, SourceAccessException.Reason.RATE_LIMITED, e.getMessage(), e);
        }
        SourceAccessException.Reason reason = switch (e.kind()) {
            case NOT_FOUND -> SourceAccessException.Reason.NOT_FOUND;
            case UNAVAILABLE -> SourceAccessException.Reason.UNAVAILABLE;
            case ACCESS_DENIED -> SourceAccessException.Reason.ACCESS_DENIED;
            case INVALID_RESPONSE -> SourceAccessException.Reason.INVALID_RESPONSE;
            case UNSUPPORTED -> SourceAccessException.Reason.UNSUPPORTED;
        };
        if (LocalFilesSourceAdapter.TYPE.equals(type)) {
            // Meldungen der Dateiquelle nennen Pfade auf dem Server; sie reichen bis in Sync-Ergebnisse und Logs.
            return new SourceAccessException(id, reason, "Dateiquelle: " + reason, null);
        }
        if (discovering()) {
            // Meldungen dieser Adapter nennen Seitentitel, Pfade oder Jobnamen der Quelle; ebenso nur die Art.
            return new SourceAccessException(id, reason, "Quelle: " + reason, null);
        }
        return new SourceAccessException(id, reason, e.getMessage(), e);
    }

    /** Die Einmalcode-Abfrage des Adapters als Abfrage des Core; Sitzung und Formular bleiben im Adapter. */
    private final class Challenge implements SignInChallenge {

        private final OneTimeCodeChallenge challenge;

        Challenge(OneTimeCodeChallenge challenge) {
            this.challenge = challenge;
        }

        @Override
        public String service() {
            return challenge.service();
        }

        @Override
        public String title() {
            return challenge.title();
        }

        @Override
        public String message() {
            return challenge.message();
        }

        @Override
        public String error() {
            return challenge.error();
        }

        @Override
        public boolean resendAvailable() {
            return challenge.resendAvailable();
        }

        @Override
        public SignInChallenge submit(char[] code) {
            try {
                return next(challenge.submit(code));
            } catch (KnowledgeSourceException e) {
                throw accessFailure(e);
            }
        }

        @Override
        public SignInChallenge resend() {
            try {
                return next(challenge.resend());
            } catch (KnowledgeSourceException e) {
                throw accessFailure(e);
            }
        }

        private SignInChallenge next(OneTimeCodeChallenge again) {
            return again == null ? null : new Challenge(again);
        }
    }
}
