package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.source.api.AddressableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.DownloadAccess;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.OneTimeCodeChallenge;
import com.aresstack.enterpriseai.source.api.OneTimeCodeRequiredException;
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.ImageSource;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceRestClient.Attachment;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceRestClient.Page;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceRestClient.Session;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
 * Confluence Data Center als {@link SearchableKnowledgeSource}.
 *
 * <ul>
 *   <li>Ohne Startpunkte ({@code space:*}, Standard wie in MainframeMate): alle Seiten aller Spaces außer den
 *       abgehakten ({@link SourceScope#excludedPartitions()}); die Spaces listet {@link #partitions()}.</li>
 *   <li>Startpunkte (nur noch aus der Datei): Seiten-ID ({@code 123456}, {@code page:123456}), Space ({@code DEV}, {@code space:DEV} –
 *       beginnt bei dessen Startseite) oder eine Ressourcen-ID dieser Quelle. {@code discover} folgt per
 *       Breitensuche den Kindseiten bis {@code maxDepth}; textartige Anhänge sind, wenn konfiguriert, Kinder
 *       ihrer Seite.</li>
 *   <li>IDs: {@code confluence:<locator>/page/<id>} und {@code confluence:<locator>/attachment/<attId>} (Locator = kanonische Basis-URL ohne Schema).</li>
 *   <li>Revision: Versionsnummer und Änderungszeitpunkt aus Confluence.</li>
 *   <li>Inhalt: gerenderte Ansicht ({@code body.view}), in Text mit Markdown-Struktur überführt.</li>
 *   <li>Lese-Ansicht ({@link PageViewSource}): {@code body.export_view} (Rückfall {@code body.view}) als HTML
 *       mit den Bildern an ihrer Stelle; deren Bytes lädt {@link #loadViewImage} angemeldet von dieser Confluence.</li>
 *   <li>Anhänge der Lese-Ansicht: alle Anhänge der Seite ({@code /child/attachment}, unabhängig von
 *       {@code includeAttachments}); die Originaldatei lädt {@link #loadAttachment} über den Download-Pfad, Vorschauen
 *       {@link #loadAttachmentPreview} über die Dokumentkonvertierung ({@code /rest/documentConversion/latest/
 *       conversion/<art>/<id>/<version>}, Muster aus Seite und Files-Endpunkt eines echten Mitschnitts).</li>
 *   <li>Search (issue #150): CQL {@code siteSearch ~ "..." AND type=page} over the search endpoint
 *       {@code /rest/api/search} with {@code excerpt=highlight}, like the Confluence web search, optionally limited
 *       to configured spaces; every hit carries the normalised excerpt as plain-text snippet without loading the
 *       page, in Confluence order. Content and navigation queries keep using the content endpoints.</li>
 *   <li>Navigation ({@link PageNavigationSource}, Issue #97): Wurzel ist der Space der Seite ({@code space:KEY}),
 *       darunter die Seiten ohne Eltern ({@code depth=root}), je Ebene die Kindseiten, blätternd in Stapeln der
 *       Seitengröße; Querverweise sind die Seitenlinks des gerenderten Inhalts, geprüft über CQL {@code id in (...)}.
 *       Eingehende Verweise liefert Confluence nicht zuverlässig, sie gelten als nicht unterstützt.</li>
 * </ul>
 *
 * <p>Anmeldedaten löst der Adapter je Port-Aufruf genau einmal über den {@link SecretProvider} auf (nicht je
 * HTTP-Request, weil z. B. KeePassRPC dafür eine Verbindung aufbaut) und vergisst sie mit dem Ende des Aufrufs.
 * Ohne {@link ConfluenceConfig#credentialRef()} fragt er keine an (z. B. reine mTLS-Anmeldung).
 */
public final class ConfluenceKnowledgeSource implements SearchableKnowledgeSource, ImageSource, PageViewSource,
        AddressableKnowledgeSource, PageNavigationSource, PartitionListingSource, ResourceMetadataSource,
        StoredContentSource {

    static final String PAGE_CONTENT_TYPE = "text/html";

    private static final Logger LOG = Logger.getLogger(ConfluenceKnowledgeSource.class.getName());
    /** So viele Anhänge zeigt der Lesebereich je Seite höchstens. */
    static final int MAX_READER_ATTACHMENTS = 500;
    /** Größte Datei, die der Lesebereich lädt (100 MB). */
    static final int READER_MAX_BYTES = 100 * 1024 * 1024;
    /**
     * Höchstens so viele Seiten fordert ein Stapel von {@link #resources} an (Confluence kappt
     * {@code /rest/api/content} ohnehin, je nach Version bei 100 bis 200). Begrenzt nur die HTTP-Antwort, nicht die
     * Gesamtzahl.
     */
    static final int MAX_PARTITION_BATCH = 100;
    /**
     * {@link StoredContent#nativeFormat()} der Seiten-Snapshots: REST-Content-JSON mit der sortierten
     * {@code expand}-Liste; eine andere Liste ergibt ein anderes, nicht vergleichbares Format.
     */
    static final String SNAPSHOT_FORMAT = "confluence-rest-content+json;expand="
            + sortedExpand(ConfluenceRestClient.SNAPSHOT_EXPAND);
    private static final String PARTITION_CURSOR_PREFIX = "p1.";
    private static final Pattern PARTITION_CURSOR = Pattern.compile("p1\\.([A-Za-z0-9_-]{1,400})\\.([0-9]{1,9})");

    /** Key and start point prefix of a space ({@link ConfluenceIds#startPoint}). */
    static final String SPACE_PREFIX = "space:";

    private final SourceAddress sourceId;
    /** Kanonische Basis-URL ohne Schema, Teil jeder Ressourcen-ID. */
    private final String locator;
    private final ConfluenceConfig config;
    private final Pattern addressPattern;
    private final ConfluenceRestClient client;
    private final SecretProvider secrets;
    private final VaultEntryOptions options;

    /**
     * @param transport z. B. {@link UrlConnectionConfluenceTransport} mit Proxy/mTLS aus der Composition Root
     * @param secrets   Security-Port; wird nur benutzt, wenn {@link ConfluenceConfig#credentialRef()} gesetzt ist
     */
    public ConfluenceKnowledgeSource(SourceAddress sourceId, ConfluenceConfig config,
                                     ConfluenceHttpTransport transport, SecretProvider secrets) {
        this(sourceId, config, transport, secrets, new VaultEntryOptions(secrets, config == null ? null
                : config.credentialRef()));
    }

    ConfluenceKnowledgeSource(SourceAddress sourceId, ConfluenceConfig config,
                              ConfluenceHttpTransport transport, SecretProvider secrets, VaultEntryOptions options) {
        if (sourceId == null || config == null || transport == null) {
            throw new IllegalArgumentException("sourceId, config und transport sind Pflicht");
        }
        if (config.credentialRef() != null && secrets == null) {
            throw new IllegalArgumentException("credentialRef gesetzt, aber kein SecretProvider");
        }
        this.sourceId = sourceId;
        this.locator = locatorOf(config);
        this.config = config;
        this.addressPattern = ConfluenceLinks.addressPattern();
        this.client = new ConfluenceRestClient(config, transport);
        this.secrets = secrets;
        this.options = options;
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

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
        final ConfluenceIds.Parsed parsed = parse(resourceId);
        return withSession(new Operation<KnowledgeDocument>() {
            @Override
            public KnowledgeDocument run(Session session) throws KnowledgeSourceException {
                return parsed.attachment ? loadAttachment(session, parsed.contentId) : loadPage(session, parsed.contentId);
            }
        });
    }

    @Override
    public List<KnowledgeResource> imagesOf(KnowledgeResourceId page, final int max) throws KnowledgeSourceException {
        final ConfluenceIds.Parsed parsed = parse(page);
        if (parsed.attachment || max <= 0) {
            return new ArrayList<KnowledgeResource>();
        }
        return withSession(new Operation<List<KnowledgeResource>>() {
            @Override
            public List<KnowledgeResource> run(Session session) throws KnowledgeSourceException {
                List<KnowledgeResource> images = new ArrayList<KnowledgeResource>();
                for (Attachment attachment : client.attachments(session, parsed.contentId, max,
                        new java.util.function.Predicate<Attachment>() {
                            @Override
                            public boolean test(Attachment candidate) {
                                return isLoadableImage(candidate);
                            }
                        })) {
                    images.add(attachmentResource(attachment));
                }
                return images;
            }
        });
    }

    @Override
    public byte[] loadImage(KnowledgeResourceId image) throws KnowledgeSourceException {
        final ConfluenceIds.Parsed parsed = parse(image);
        if (!parsed.attachment) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Kein Anhang: " + image.value());
        }
        return withSession(new Operation<byte[]>() {
            @Override
            public byte[] run(Session session) throws KnowledgeSourceException {
                Attachment attachment = client.attachment(session, parsed.contentId);
                if (!isLoadableImage(attachment)) {
                    throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Anhang " + parsed.contentId
                            + " ist kein ladbares Bild (" + attachment.mediaType + ")");
                }
                return client.download(session, attachment);
            }
        });
    }

    @Override
    public PageView pageView(KnowledgeResourceId page) throws KnowledgeSourceException {
        final ConfluenceIds.Parsed parsed = parse(page);
        if (parsed.attachment) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Keine Seite: " + page.value());
        }
        return withSession(new Operation<PageView>() {
            @Override
            public PageView run(Session session) throws KnowledgeSourceException {
                Page loaded = client.readingPage(session, parsed.contentId);
                String base = config.baseUrl().toString();
                base = base.endsWith("/") ? base : base + "/";
                ConfluencePageView view = ConfluencePageView.of(loaded.html, base, loaded.serverBase,
                        loaded.storage, loaded.id);
                List<PageView.Image> images = new ArrayList<PageView.Image>();
                for (ConfluencePageView.InlineImage image : view.images) {
                    images.add(new PageView.Image(image.src, image.title, image.attachmentId == null ? null
                            : inlineImageResource(image, loaded.id)));
                }
                return new PageView(view.html, base, images, readerAttachments(session, loaded.id));
            }
        });
    }

    /**
     * Alle Anhänge der Seite für den Lesebereich, ohne Filter und unabhängig von {@link ConfluenceConfig#includeAttachments()}
     * (der Schalter gilt der Indexierung). Scheitert die Liste, bleibt die Seite lesbar, nur ohne Anhänge.
     */
    private List<PageView.Attachment> readerAttachments(Session session, String pageId) {
        List<PageView.Attachment> result = new ArrayList<PageView.Attachment>();
        try {
            for (Attachment attachment : client.attachments(session, pageId, MAX_READER_ATTACHMENTS,
                    candidate -> true)) {
                result.add(new PageView.Attachment(attachment.id, attachment.containerId, attachment.title,
                        attachment.mediaType, attachment.version, attachment.size, attachment.downloadPath,
                        attachment.webUi, EnumSet.allOf(PageView.Preview.class)));
            }
        } catch (KnowledgeSourceException e) {
            LOG.warning("[Confluence] Anhänge der Seite " + pageId + " nicht gelesen (" + e.kind() + "): "
                    + e.getMessage());
        }
        return result;
    }

    /**
     * Die Originaldatei über denselben angemeldeten Weg wie die Seite. Die Angaben des Anhangs werden frisch aus
     * Confluence gelesen (Download-Pfad und Version nicht vom Aufrufer übernommen).
     */
    @Override
    public byte[] loadAttachment(PageView.Attachment attachment) throws KnowledgeSourceException {
        final String id = attachmentIdOf(attachment);
        return file(new Operation<byte[]>() {
            @Override
            public byte[] run(Session session) throws KnowledgeSourceException {
                return client.readAttachment(session, client.attachment(session, id), readerMaxBytes());
            }
        });
    }

    /**
     * Vorschau aus Confluences Dokumentkonvertierung ({@code convert}, {@code poster}, {@code thumbnail}); ob es sie
     * für den Typ gibt, weiß erst Confluence (sonst {@code NOT_FOUND} bzw. {@code INVALID_RESPONSE}).
     */
    @Override
    public byte[] loadAttachmentPreview(PageView.Attachment attachment, final PageView.Preview preview)
            throws KnowledgeSourceException {
        final String id = attachmentIdOf(attachment);
        if (preview == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Vorschauart fehlt");
        }
        return file(new Operation<byte[]>() {
            @Override
            public byte[] run(Session session) throws KnowledgeSourceException {
                return client.conversion(session, client.attachment(session, id), conversionKind(preview),
                        readerMaxBytes());
            }
        });
    }

    /** Alle Anhänge der Seite als Ressourcen, ohne Filter und unabhängig von {@code includeAttachments}. */
    @Override
    public List<KnowledgeResource> attachmentsOf(KnowledgeResourceId page) throws KnowledgeSourceException {
        final ConfluenceIds.Parsed parsed = parse(page);
        if (parsed.attachment) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Keine Seite: " + page.value());
        }
        return withSession(new Operation<List<KnowledgeResource>>() {
            @Override
            public List<KnowledgeResource> run(Session session) throws KnowledgeSourceException {
                List<KnowledgeResource> result = new ArrayList<KnowledgeResource>();
                for (Attachment attachment : client.attachments(session, parsed.contentId, MAX_READER_ATTACHMENTS,
                        candidate -> true)) {
                    result.add(attachmentResource(attachment));
                }
                return result;
            }
        });
    }

    /**
     * Jede HTTP(S)-Adresse und jeder Bookmark-artige Wert mit dem Confluence-Schema; ob sie zu dieser Confluence
     * gehört, entscheidet erst {@link #resolveAddress}.
     */
    @Override
    public Pattern addressPattern() {
        return addressPattern;
    }

    /**
     * Adressen unter der eingestellten Basis-URL auf eine Seite ({@link ConfluenceLinks}), für Suchleiste, Werkzeug
     * und Links im Lesebereich; Space und Titel fragt die Quelle nach. Was nicht auflösbar ist (fremde Adresse, keine
     * Seitenform, Seite unbekannt), ist {@code null}.
     */
    @Override
    public KnowledgeResourceId resolveAddress(String address) throws KnowledgeSourceException {
        String base = config.baseUrl().toString();
        final ConfluenceLinks.Target target = ConfluenceLinks.parse(
                ConfluenceLinks.webAddressOf(address, locator, base), base);
        if (target == null) {
            return null;
        }
        if (target.pageId != null) {
            return ConfluenceIds.page(locator, target.pageId);
        }
        String pageId = withSession(new Operation<String>() {
            @Override
            public String run(Session session) throws KnowledgeSourceException {
                if (target.title != null) {
                    return client.pageIdByTitle(session, target.spaceKey, target.title);
                }
                ConfluenceRestClient.Space space = client.space(session, target.spaceKey);
                return space == null ? null : space.homepageId;
            }
        });
        return ConfluenceIds.isContentId(pageId) ? ConfluenceIds.page(locator, pageId) : null;
    }

    @Override
    public boolean isAttachment(KnowledgeResourceId id) {
        ConfluenceIds.Parsed parsed = id == null ? null : ConfluenceIds.parse(locator, id);
        return parsed != null && parsed.attachment;
    }

    /**
     * Die Originaldatei eines Anhangs mit frischen Angaben aus Confluence, über denselben Dateiweg wie der Lesebereich
     * (Einmalcode-Abfrage, Grenze für Lesen).
     */
    @Override
    public SourceFile loadAttachmentFile(KnowledgeResourceId attachment) throws KnowledgeSourceException {
        final ConfluenceIds.Parsed parsed = parse(attachment);
        if (!parsed.attachment) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Kein Anhang: " + attachment.value());
        }
        final Attachment[] described = new Attachment[1];
        byte[] content = file(new Operation<byte[]>() {
            @Override
            public byte[] run(Session session) throws KnowledgeSourceException {
                described[0] = client.attachment(session, parsed.contentId);
                return client.readAttachment(session, described[0], readerMaxBytes());
            }
        });
        return new SourceFile(attachmentResource(described[0]), content);
    }

    static String conversionKind(PageView.Preview preview) {
        switch (preview) {
            case DOCUMENT:
                return "convert";
            case POSTER:
                return "poster";
            default:
                return "thumbnail";
        }
    }

    private static String attachmentIdOf(PageView.Attachment attachment) throws KnowledgeSourceException {
        if (attachment == null || !ConfluenceIds.isAttachmentId(attachment.id())) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Kein Confluence-Anhang: "
                    + (attachment == null ? "null" : attachment.id()));
        }
        return attachment.id();
    }

    /**
     * Grenze für Anhänge im Lesebereich: höchstens {@link #READER_MAX_BYTES}, mindestens die Grenze der Indexierung
     * ({@code maxAttachmentBytes}, Standard 5 MB, für Lesen zu knapp).
     */
    private int readerMaxBytes() {
        return Math.max(config.maxAttachmentBytes(), READER_MAX_BYTES);
    }

    /** Wie MainframeMates {@code ByteDownloader} im {@code ConfluenceReaderTab}: jede Adresse unter der Basis-URL. */
    @Override
    public byte[] loadViewImage(String requested) throws KnowledgeSourceException {
        String base = config.baseUrl().toString();
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        // Gleicher Host in anderer Schreibweise (Groß-/Kleinschreibung, Standardport) ist nicht fremd.
        final String src = ConfluencePageView.onBase(requested, base, base, false);
        if (src == null || !src.startsWith(base + "/")) {
            // Fremde Bilder werden nie geladen (Angelo); der Text nennt beide Adressen, damit eine abweichende
            // Basis (etwa die Server-Basis-URL in export_view) sofort sichtbar ist.
            String text = "Bild liegt nicht unter der eingestellten Confluence-Adresse " + base
                    + " und wird nicht geladen: " + ConfluenceRestClient.withoutQuery(String.valueOf(src));
            LOG.warning("[Confluence] Bild nicht geladen: url=" + ConfluenceRestClient.withoutQuery(String.valueOf(src))
                    + " status=- contentType=- bytes=- fehler="
                    + Kind.UNSUPPORTED + ": " + text);
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, text);
        }
        final String path = src.substring(base.length());
        return file(new Operation<byte[]>() {
            @Override
            public byte[] run(Session session) throws KnowledgeSourceException {
                return client.viewImage(session, path);
            }
        });
    }

    private KnowledgeResource inlineImageResource(ConfluencePageView.InlineImage image, String pageId) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("confluence.contentId", image.attachmentId);
        metadata.put("confluence.type", "attachment");
        String container = ConfluenceIds.isContentId(image.containerId) ? image.containerId : pageId;
        return KnowledgeResource.builder(ConfluenceIds.attachment(locator, image.attachmentId), sourceId)
                .title(image.title)
                .contentType(image.mediaType.isEmpty() ? null : image.mediaType)
                .revision(image.version.isEmpty() ? KnowledgeRevision.unknown()
                        : KnowledgeRevision.version(image.version))
                .parentId(ConfluenceIds.page(locator, container))
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    /** Bilder: {@code image/*} außer SVG (kein Rasterbild für OCR/Bildverständnis), höchstens so groß wie Anhänge. */
    private boolean isLoadableImage(Attachment attachment) {
        String type = baseType(attachment.mediaType);
        return type.startsWith("image/") && !type.contains("svg")
                && (attachment.size < 0 || attachment.size <= config.maxAttachmentBytes());
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        final ConfluenceIds.Parsed parsed = parse(resourceId);
        return withSession(new Operation<List<SourceLink>>() {
            @Override
            public List<SourceLink> run(Session session) throws KnowledgeSourceException {
                List<SourceLink> links = new ArrayList<SourceLink>();
                if (parsed.attachment) {
                    client.attachment(session, parsed.contentId);
                    return links;
                }
                client.page(session, parsed.contentId, false);
                for (KnowledgeResource child : childrenOf(session, parsed.contentId, Integer.MAX_VALUE)) {
                    links.add(new SourceLink(child.id(), child.title()));
                }
                return links;
            }
        });
    }

    // --- Navigation (issue #97) -----------------------------------------------------------------------------

    @Override
    public PagePosition position(KnowledgeResourceId page) throws KnowledgeSourceException {
        final ConfluenceIds.Parsed parsed = parse(page);
        if (parsed.attachment) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Anhänge stehen nicht im Seitenbaum: " + page.value());
        }
        ConfluenceRestClient.Lineage lineage = withSession(new Operation<ConfluenceRestClient.Lineage>() {
            @Override
            public ConfluenceRestClient.Lineage run(Session session) throws KnowledgeSourceException {
                return client.lineage(session, parsed.contentId);
            }
        });
        String rootKey = SPACE_PREFIX + lineage.spaceKey;
        NavigationNode root = NavigationNode.root(rootKey, lineage.spaceName.isEmpty() ? lineage.spaceKey
                : lineage.spaceName, rootKey, NavigationNode.Children.UNKNOWN);
        List<NavigationNode> ancestors = new ArrayList<NavigationNode>();
        for (ConfluenceRestClient.TreePage ancestor : lineage.ancestors) {
            ancestors.add(treeNode(ancestor));
        }
        return new PagePosition(root, ancestors, treeNode(lineage.page));
    }

    @Override
    public NavigationChildren children(NavigationNode parent, String cursor) throws KnowledgeSourceException {
        if (parent == null) {
            throw new IllegalArgumentException("parent must not be null");
        }
        final String first;
        final String expectedPrefix;
        if (parent.isRoot()) {
            String spaceKey = parent.key().startsWith(SPACE_PREFIX) ? parent.key().substring(SPACE_PREFIX.length())
                    : "";
            if (!ConfluenceIds.isSpaceKey(spaceKey)) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Kein Space dieser Quelle: " + parent.key());
            }
            first = client.rootPagesPath(spaceKey);
        } else {
            ConfluenceIds.Parsed parsed = parse(parent.resource());
            if (parsed.attachment) {
                return new NavigationChildren(null, null);
            }
            first = client.childPagesPath(parsed.contentId);
        }
        expectedPrefix = first.substring(0, first.indexOf('?') + 1);
        final String path = cursor == null || cursor.isEmpty() ? first : cursor;
        if (!path.startsWith(expectedPrefix)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Folgelink gehört nicht zu " + parent.key());
        }
        ConfluenceRestClient.TreeBatch batch = withSession(new Operation<ConfluenceRestClient.TreeBatch>() {
            @Override
            public ConfluenceRestClient.TreeBatch run(Session session) throws KnowledgeSourceException {
                return client.treeBatch(session, path);
            }
        });
        List<NavigationNode> nodes = new ArrayList<NavigationNode>();
        for (ConfluenceRestClient.TreePage child : batch.pages) {
            nodes.add(treeNode(child));
        }
        return new NavigationChildren(nodes, batch.next);
    }

    /**
     * Alle Seiten eines Space ohne Inhalt und ohne Anhänge, flach in Confluence-Reihenfolge
     * ({@code /rest/api/content?type=page&spaceKey=...}), auch Seiten außerhalb des Baums der Startseite. Ein weiterer
     * Stapel folgt genau dann, wenn Confluence einen {@code next}-Link liefert; es gibt keine Obergrenze für die
     * Gesamtzahl. Der Cursor ist undurchsichtig und an den Space gebunden ({@link #partitionCursor}); ein Stapel umfasst
     * höchstens {@link #MAX_PARTITION_BATCH} Seiten, auch wenn {@code limit} größer ist.
     */
    @Override
    public PartitionResources resources(final String partitionKey, String cursor, final int limit)
            throws KnowledgeSourceException {
        if (!ConfluenceIds.isSpaceKey(partitionKey)) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Kein Space dieser Quelle: " + partitionKey);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        final boolean first = cursor == null || cursor.isEmpty();
        final int start = first ? 0 : partitionCursorStart(partitionKey, cursor);
        final int batchSize = Math.min(limit, MAX_PARTITION_BATCH);
        ConfluenceRestClient.PageBatch batch = withSession(new Operation<ConfluenceRestClient.PageBatch>() {
            @Override
            public ConfluenceRestClient.PageBatch run(Session session) throws KnowledgeSourceException {
                // Ein unbekannter Space liefert eine leere Liste; nur am Anfang eigens nachfragen.
                if (first && client.space(session, partitionKey) == null) {
                    throw new KnowledgeSourceException(Kind.NOT_FOUND, "Space nicht gefunden: " + partitionKey);
                }
                return client.spacePageBatch(session, partitionKey, start, batchSize);
            }
        });
        List<KnowledgeResource> resources = new ArrayList<KnowledgeResource>();
        for (Page page : batch.pages) {
            resources.add(pageResource(page, page.parentId));
        }
        return new PartitionResources(resources,
                batch.nextStart < 0 ? "" : partitionCursor(partitionKey, batch.nextStart));
    }

    /**
     * Cursor für die Position {@code start} in einem Space: {@code p1.<Space-Key Base64url>.<start>}. Das Präfix
     * versioniert das Format, der eingebettete Space-Key bindet den Cursor an seine Partition.
     */
    static String partitionCursor(String spaceKey, int start) {
        return PARTITION_CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(spaceKey.getBytes(StandardCharsets.UTF_8)) + "." + start;
    }

    /** Position aus einem Cursor von {@link #partitionCursor}; {@code UNSUPPORTED}, wenn er nicht zu diesem Space gehört. */
    private static int partitionCursorStart(String spaceKey, String cursor) throws KnowledgeSourceException {
        Matcher matcher = PARTITION_CURSOR.matcher(cursor);
        if (matcher.matches()) {
            String key;
            try {
                key = new String(Base64.getUrlDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                key = null;
            }
            if (spaceKey.equals(key)) {
                return Integer.parseInt(matcher.group(2));
            }
        }
        throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Cursor gehört nicht zu Space " + spaceKey);
    }

    /** Die Content-ID einer Seite dieser Quelle; {@code null} für Anhänge und fremde IDs. */
    @Override
    public String localId(KnowledgeResourceId resourceId) {
        ConfluenceIds.Parsed parsed = resourceId == null ? null : ConfluenceIds.parse(locator, resourceId);
        return parsed == null || parsed.attachment ? null : parsed.contentId;
    }

    /**
     * Metadaten einer Seite ohne Inhalt ({@code /rest/api/content/<id>?expand=version,space,metadata.labels,
     * ancestors,history}), dieselben Felder wie in {@link #resources}; unabhängig vom Space, in dem die Seite liegt.
     */
    @Override
    public KnowledgeResource resourceMetadata(final String localId) throws KnowledgeSourceException {
        if (!ConfluenceIds.isContentId(localId)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Keine Seiten-ID dieser Quelle: " + localId);
        }
        Page page = withSession(new Operation<Page>() {
            @Override
            public Page run(Session session) throws KnowledgeSourceException {
                return client.page(session, localId, false);
            }
        });
        return pageResource(page, page.parentId);
    }

    /**
     * Die unveränderte JSON-Antwort einer Seite ({@code /rest/api/content/<id>?expand=}{@value
     * ConfluenceRestClient#SNAPSHOT_EXPAND}) mit den Metadaten aus derselben Antwort, wie {@link #resourceMetadata}.
     */
    @Override
    public StoredContent storedContent(final String localId) throws KnowledgeSourceException {
        if (!ConfluenceIds.isContentId(localId)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Keine Seiten-ID dieser Quelle: " + localId);
        }
        ConfluenceRestClient.StoredPage stored = withSession(new Operation<ConfluenceRestClient.StoredPage>() {
            @Override
            public ConfluenceRestClient.StoredPage run(Session session) throws KnowledgeSourceException {
                return client.storedPage(session, localId);
            }
        });
        return new StoredContent(pageResource(stored.page, stored.page.parentId), stored.payload,
                mediaType(stored.contentType), SNAPSHOT_FORMAT, Instant.now());
    }

    private static String sortedExpand(String expand) {
        List<String> fields = new ArrayList<String>(Arrays.asList(expand.split(",")));
        Collections.sort(fields);
        return join(fields);
    }

    /** Medientyp ohne Parameter ({@code application/json;charset=UTF-8} → {@code application/json}). */
    private static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
        return type.isEmpty() ? "application/json" : type.toLowerCase(Locale.ROOT);
    }

    /** Outgoing page links of the rendered body; Confluence has no reliable list of incoming links. */
    @Override
    public CrossReferences crossReferences(KnowledgeResourceId page) throws KnowledgeSourceException {
        final ConfluenceIds.Parsed parsed = parse(page);
        if (parsed.attachment) {
            return CrossReferences.outgoingOnly(null);
        }
        return withSession(new Operation<CrossReferences>() {
            @Override
            public CrossReferences run(Session session) throws KnowledgeSourceException {
                Page loaded = client.page(session, parsed.contentId, true);
                List<String> ids = new ArrayList<String>();
                for (ConfluenceLinks.Target target : ConfluencePageLinks.of(loaded.html, config.baseUrl().toString(),
                        loaded.id)) {
                    String id = targetId(session, target);
                    if (ConfluenceIds.isContentId(id) && !id.equals(loaded.id) && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
                Map<String, String> visible = client.visiblePageTitles(session, ids);
                List<SourceLink> outgoing = new ArrayList<SourceLink>();
                for (String id : ids) {
                    if (visible.containsKey(id)) {
                        outgoing.add(new SourceLink(ConfluenceIds.page(locator, id), visible.get(id)));
                    }
                }
                return CrossReferences.outgoingOnly(outgoing);
            }
        });
    }

    /** The content id behind a link target; {@code null} if Confluence does not know it. */
    private String targetId(Session session, ConfluenceLinks.Target target) throws KnowledgeSourceException {
        if (target.pageId != null) {
            return target.pageId;
        }
        try {
            if (target.title != null) {
                return client.pageIdByTitle(session, target.spaceKey, target.title);
            }
            ConfluenceRestClient.Space space = client.space(session, target.spaceKey);
            return space == null ? null : space.homepageId;
        } catch (KnowledgeSourceException e) {
            if (e.kind() == Kind.NOT_FOUND || e.kind() == Kind.ACCESS_DENIED) {
                return null; // a dead or restricted link is no cross reference
            }
            throw e;
        }
    }

    private NavigationNode treeNode(ConfluenceRestClient.TreePage page) {
        NavigationNode.Children children = page.hasChildren == null ? NavigationNode.Children.UNKNOWN
                : page.hasChildren ? NavigationNode.Children.YES : NavigationNode.Children.NO;
        return NavigationNode.page(ConfluenceIds.page(locator, page.id), page.title, children);
    }

    @Override
    public List<SourcePartition> partitions() throws KnowledgeSourceException {
        return withSession(new Operation<List<SourcePartition>>() {
            @Override
            public List<SourcePartition> run(Session session) throws KnowledgeSourceException {
                List<SourcePartition> partitions = new ArrayList<SourcePartition>();
                for (ConfluenceRestClient.Space space : client.spaces(session, Integer.MAX_VALUE)) {
                    KnowledgeResourceId home = ConfluenceIds.isContentId(space.homepageId)
                            ? ConfluenceIds.page(locator, space.homepageId) : null;
                    partitions.add(new SourcePartition(space.key, space.name, home, "space:" + space.key));
                }
                return partitions;
            }
        });
    }

    @Override
    public List<SourceSearchHit> search(final SourceQuery query) throws KnowledgeSourceException {
        final String cql = cql(query.text(), config.searchSpaceKeys(), query.excludedPartitions());
        return withSession(new Operation<List<SourceSearchHit>>() {
            @Override
            public List<SourceSearchHit> run(Session session) throws KnowledgeSourceException {
                List<SourceSearchHit> hits = new ArrayList<SourceSearchHit>();
                for (Page page : client.search(session, cql, query.limit()).pages) {
                    // The search delivers version and URL (expand=content.version): unchanged hits are not loaded.
                    hits.add(new SourceSearchHit(ConfluenceIds.page(locator, page.id), page.title, page.excerpt,
                            KnowledgeRevision.of(page.modifiedAt, page.version), location(page.webUi)));
                }
                return hits;
            }
        });
    }

    @Override
    public String toString() {
        return "ConfluenceKnowledgeSource[" + sourceId + ", " + config + "]";
    }

    // --- discover -----------------------------------------------------------------------------------------

    private List<KnowledgeResource> crawl(Session session, SourceScope scope) throws KnowledgeSourceException {
        Map<KnowledgeResourceId, KnowledgeResource> found = new LinkedHashMap<KnowledgeResourceId, KnowledgeResource>();
        Deque<Node> queue = new ArrayDeque<Node>();
        for (String raw : scope.startPoints()) {
            if (ConfluenceSourceProvider.ALL_SPACES.equals(raw.trim())) {
                for (String key : spaceKeys(session, scope)) {
                    allPagesOf(session, key, scope.maxResources(), found);
                }
                continue;
            }
            ConfluenceIds.StartPoint point = ConfluenceIds.startPoint(locator, raw);
            if (point == null) {
                continue;
            }
            Page start;
            if (point.spaceKey != null) {
                ConfluenceRestClient.Space space = client.space(session, point.spaceKey);
                if (space == null || scope.excludedPartitions().contains(space.key)) {
                    continue;
                }
                if (!ConfluenceIds.isContentId(space.homepageId)) {
                    // Space ohne Startseite: wie MainframeMate (space="KEY" AND type=page) alle Seiten des Space.
                    allPagesOf(session, space.key, scope.maxResources(), found);
                    continue;
                }
                start = existingPage(session, space.homepageId);
            } else {
                start = existingPage(session, point.pageId);
            }
            if (start != null && !scope.excludedPartitions().contains(start.spaceKey)) {
                queue.add(new Node(pageResource(start, start.parentId), start.id, 0));
            }
        }
        while (!queue.isEmpty() && found.size() < scope.maxResources()) {
            Node node = queue.poll();
            if (found.containsKey(node.resource.id())) {
                continue;
            }
            found.put(node.resource.id(), node.resource);
            int remaining = scope.maxResources() - found.size(); // nur so viele Kinder holen, wie noch Platz ist
            if (node.pageId == null || node.depth >= scope.maxDepth() || remaining <= 0) {
                continue;
            }
            List<KnowledgeResource> children;
            try {
                children = childrenOf(session, node.pageId, remaining);
            } catch (KnowledgeSourceException e) {
                if (e.kind() == Kind.NOT_FOUND) {
                    continue; // Seite zwischenzeitlich gelöscht: überspringen, wie im Port beschrieben
                }
                throw e;
            }
            for (KnowledgeResource child : children) {
                ConfluenceIds.Parsed parsed = ConfluenceIds.parse(locator, child.id());
                queue.add(new Node(child, parsed.attachment ? null : parsed.contentId, node.depth + 1));
            }
        }
        return new ArrayList<KnowledgeResource>(found.values());
    }

    /**
     * Die Spaces für {@code space:*}: die Space-Schlüssel der Quelle oder, ohne sie, alle Spaces (wie MainframeMate),
     * jeweils ohne die abgehakten.
     */
    private List<String> spaceKeys(Session session, SourceScope scope) throws KnowledgeSourceException {
        List<String> keys = new ArrayList<String>();
        if (config.searchSpaceKeys().isEmpty()) {
            for (ConfluenceRestClient.Space space : client.spaces(session, Integer.MAX_VALUE)) {
                keys.add(space.key);
            }
        } else {
            keys.addAll(config.searchSpaceKeys());
        }
        keys.removeAll(scope.excludedPartitions());
        return keys;
    }

    /** Alle Seiten eines Space (samt textartiger Anhänge, wenn konfiguriert), bis {@code max} beisammen sind. */
    private void allPagesOf(Session session, String spaceKey, int max,
                            Map<KnowledgeResourceId, KnowledgeResource> found) throws KnowledgeSourceException {
        if (found.size() >= max) {
            return;
        }
        for (Page page : client.spacePages(session, spaceKey, max - found.size())) {
            if (found.size() >= max) {
                return;
            }
            KnowledgeResource resource = pageResource(page, page.parentId);
            found.put(resource.id(), resource);
            if (config.includeAttachments() && found.size() < max) {
                for (Attachment attachment : client.attachments(session, page.id, max - found.size(),
                        this::isIndexable)) {
                    KnowledgeResource attached = attachmentResource(attachment);
                    found.put(attached.id(), attached);
                }
            }
        }
    }

    private Page existingPage(Session session, String pageId) throws KnowledgeSourceException {
        try {
            return client.page(session, pageId, false);
        } catch (KnowledgeSourceException e) {
            if (e.kind() == Kind.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    /** Kindseiten und (wenn konfiguriert) textartige Anhänge einer Seite, in Confluence-Reihenfolge. */
    private List<KnowledgeResource> childrenOf(Session session, String pageId, int max)
            throws KnowledgeSourceException {
        Set<KnowledgeResource> children = new LinkedHashSet<KnowledgeResource>();
        for (Page child : client.children(session, pageId, max)) {
            children.add(pageResource(child, pageId));
        }
        if (config.includeAttachments() && children.size() < max) {
            for (Attachment attachment : client.attachments(session, pageId, max - children.size(), this::isIndexable)) {
                children.add(attachmentResource(attachment));
            }
        }
        return new ArrayList<KnowledgeResource>(children);
    }

    // --- load ---------------------------------------------------------------------------------------------

    private KnowledgeDocument loadPage(Session session, String pageId) throws KnowledgeSourceException {
        Page page = client.page(session, pageId, true);
        String body = ConfluenceHtmlText.toText(page.html);
        String text = body.isEmpty() ? "# " + page.title : "# " + page.title + "\n\n" + body;
        return KnowledgeDocument.of(pageResource(page, page.parentId), text,
                ConfluenceHtmlText.toBlocks(page.title, page.html));
    }

    private KnowledgeDocument loadAttachment(Session session, String attachmentId) throws KnowledgeSourceException {
        Attachment attachment = client.attachment(session, attachmentId);
        if (!isTextLike(attachment.mediaType)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED,
                    "Anhang " + attachmentId + " hat keinen Textinhalt (" + attachment.mediaType + ")");
        }
        byte[] content = client.download(session, attachment);
        String text = new String(content, charsetOf(attachment.mediaType));
        return KnowledgeDocument.of(attachmentResource(attachment), text);
    }

    // --- Abbildung ----------------------------------------------------------------------------------------

    private KnowledgeResource pageResource(Page page, String parentId) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("confluence.contentId", page.id);
        metadata.put("confluence.type", "page");
        if (page.spaceKey != null) {
            metadata.put("confluence.space", page.spaceKey);
        }
        if (!page.labels.isEmpty()) {
            metadata.put("confluence.labels", join(page.labels));
        }
        if (page.createdAt != null) {
            metadata.put("confluence.createdAt", page.createdAt.toString());
        }
        if (page.createdBy != null) {
            metadata.put("confluence.createdBy", page.createdBy);
        }
        return KnowledgeResource.builder(ConfluenceIds.page(locator, page.id), sourceId)
                .title(page.title)
                .contentType(PAGE_CONTENT_TYPE)
                .revision(KnowledgeRevision.of(page.modifiedAt, page.version))
                .parentId(parentId == null ? null : ConfluenceIds.page(locator, parentId))
                .scope(page.spaceKey == null ? "" : page.spaceKey)
                .location(location(page.webUi))
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private KnowledgeResource attachmentResource(Attachment attachment) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("confluence.contentId", attachment.id);
        metadata.put("confluence.type", "attachment");
        if (attachment.size >= 0) {
            metadata.put("confluence.fileSize", String.valueOf(attachment.size));
            metadata.put(KnowledgeMetadata.FILE_SIZE, String.valueOf(attachment.size));
        }
        return KnowledgeResource.builder(ConfluenceIds.attachment(locator, attachment.id), sourceId)
                .title(attachment.title)
                .contentType(attachment.mediaType)
                .revision(KnowledgeRevision.of(attachment.modifiedAt, attachment.version))
                .parentId(attachment.containerId == null || !ConfluenceIds.isContentId(attachment.containerId)
                        ? null : ConfluenceIds.page(locator, attachment.containerId))
                .location(location(attachment.webUi))
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private boolean isIndexable(Attachment attachment) {
        return isTextLike(attachment.mediaType)
                && (attachment.size < 0 || attachment.size <= config.maxAttachmentBytes());
    }

    static boolean isTextLike(String mediaType) {
        String type = baseType(mediaType);
        return type.startsWith("text/") || "application/json".equals(type) || "application/xml".equals(type)
                || type.endsWith("+json") || type.endsWith("+xml");
    }

    private static Charset charsetOf(String mediaType) {
        for (String part : mediaType.split(";")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                try {
                    return Charset.forName(p.substring(8).replace("\"", "").trim());
                } catch (RuntimeException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String baseType(String mediaType) {
        String type = mediaType == null ? "" : mediaType;
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    /** Aufrufbarer Ort; nur unterhalb der Basis-URL, nie mit Zugangsdaten. */
    private URI location(String webUi) {
        if (webUi == null || !webUi.startsWith("/") || webUi.startsWith("//")) {
            return null;
        }
        try {
            return URI.create(config.baseUrl() + webUi);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Der kanonische Locator der Basis-URL ({@code cf.intern/confluence}), aus dem die Ressourcen-IDs entstehen. */
    static String locatorOf(ConfluenceConfig config) {
        return SourceLocators.url(config.baseUrl().toString());
    }

    private ConfluenceIds.Parsed parse(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        ConfluenceIds.Parsed parsed = resourceId == null ? null : ConfluenceIds.parse(locator, resourceId);
        if (parsed == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED,
                    resourceId + " gehört nicht zur Confluence-Quelle " + sourceId);
        }
        return parsed;
    }

    /** CQL of the page search; quotes and backslashes in the search text are escaped. */
    static String cql(String text, List<String> spaceKeys) {
        return cql(text, spaceKeys, Collections.<String>emptySet());
    }

    /** Wie MainframeMate: abgehakte Spaces fallen über {@code space not in (...)} aus der Suche. */
    static String cql(String text, List<String> spaceKeys, Collection<String> excludedSpaces) {
        StringBuilder cql = new StringBuilder("siteSearch ~ \"").append(escapeCql(text.trim()))
                .append("\" AND type=page");
        appendSpaces(cql, " AND space in (", spaceKeys);
        appendSpaces(cql, " AND space not in (", excludedSpaces);
        return cql.toString();
    }

    private static void appendSpaces(StringBuilder cql, String clause, Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        cql.append(clause);
        int i = 0;
        for (String key : keys) {
            cql.append(i++ == 0 ? "" : ",").append('"').append(escapeCql(key)).append('"');
        }
        cql.append(')');
    }

    private static String escapeCql(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            sb.append(sb.length() == 0 ? "" : ",").append(value);
        }
        return sb.toString();
    }

    // --- Anmeldung je Vorgang -----------------------------------------------------------------------------

    /** Ob Dateien gerade ohne Einmalcode laden (Web-Sitzung dieser Quelle, nur im Speicher). */
    @Override
    public DownloadAccess downloadAccess() {
        return client.downloadAccess();
    }

    /**
     * Ein Dateiabruf der Lese-Ansicht (Bild, Anhang, Vorschau). Liefert Confluence statt der Datei die Einmalcode-Seite
     * eines Zwei-Faktor-Plugins, geht deren Formular als {@link OneTimeCodeChallenge} an den Aufrufer; nach der
     * Bestätigung lädt er neu, und weitere Dateien laden ohne Abfrage, solange die Sitzung gilt.
     */
    private byte[] file(Operation<byte[]> operation) throws KnowledgeSourceException {
        try {
            return withSession(operation);
        } catch (ConfluenceRestClient.TwoFactorRequired required) {
            OneTimeCodeForm form = client.oneTimeCodeForm(required);
            if (form == null) {
                throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "Confluence verlangt einen Einmalcode (OTP), "
                        + "die Seite hat aber kein bekanntes Formular dafür (" + required.getMessage() + ")", required);
            }
            throw new OneTimeCodeRequiredException("Confluence verlangt für Dateien einen Einmalcode (OTP) ("
                    + required.getMessage() + ")", new FormChallenge(form), required);
        }
    }

    /** Das Formular einer Einmalcode-Seite; Absenden über die Anmeldung dieser Quelle und ihre Web-Sitzung. */
    private final class FormChallenge implements OneTimeCodeChallenge {
        private final OneTimeCodeForm form;

        FormChallenge(OneTimeCodeForm form) {
            this.form = form;
        }

        @Override
        public String service() {
            return config.baseUrl().getHost();
        }

        @Override
        public String title() {
            return form.title();
        }

        @Override
        public String message() {
            return form.message();
        }

        @Override
        public String error() {
            return form.error();
        }

        @Override
        public boolean resendAvailable() {
            return form.resendAvailable();
        }

        @Override
        public OneTimeCodeChallenge submit(final char[] code) throws KnowledgeSourceException {
            if (code == null || code.length == 0) {
                throw new IllegalArgumentException("code must not be empty");
            }
            return next(withSession(new Operation<OneTimeCodeForm>() {
                @Override
                public OneTimeCodeForm run(Session session) throws KnowledgeSourceException {
                    return client.postOneTimeCode(session, form, code);
                }
            }));
        }

        @Override
        public OneTimeCodeChallenge resend() throws KnowledgeSourceException {
            return next(withSession(new Operation<OneTimeCodeForm>() {
                @Override
                public OneTimeCodeForm run(Session session) throws KnowledgeSourceException {
                    return client.postOneTimeCode(session, form, null);
                }
            }));
        }

        private OneTimeCodeChallenge next(OneTimeCodeForm again) {
            return again == null ? null : new FormChallenge(again);
        }

        @Override
        public String toString() {
            return "Einmalcode-Abfrage[" + service() + "]";
        }
    }

    private <T> T withSession(final Operation<T> operation) throws KnowledgeSourceException {
        final SecretRef ref = config.credentialRef();
        if (ref == null) {
            return operation.run(new Session(null));
        }
        try {
            if (!options.sendsLogin()) {
                return operation.run(new Session(null)); // MM_RequiresLogin=false: nur Zertifikat, wie MainframeMate
            }
            return secrets.withSecret(ref, material -> operation.run(new Session(ConfluenceAuthorization.header(material))));
        } catch (SecretUnavailableException e) {
            Kind kind = e.reason() == SecretUnavailableException.Reason.NOT_AVAILABLE ? Kind.UNAVAILABLE : Kind.ACCESS_DENIED;
            throw new KnowledgeSourceException(kind,
                    "Anmeldedaten für Confluence-Quelle " + sourceId + " nicht verfügbar (" + e.reason() + ", " + ref
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
        }
    }

    private interface Operation<T> {
        T run(Session session) throws KnowledgeSourceException;
    }

    private static final class Node {
        final KnowledgeResource resource;
        final String pageId;
        final int depth;

        Node(KnowledgeResource resource, String pageId, int depth) {
            this.resource = resource;
            this.pageId = pageId;
            this.depth = depth;
        }
    }
}
