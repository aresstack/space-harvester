package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KeywordField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;

/**
 * Feldschema {@value #VERSION} des Seiten-Caches. Drei Dokumentarten im selben Index, unterschieden durch
 * {@value #TYPE}:
 *
 * <ul>
 *   <li>{@code page} – je {@code sourceId + pageId} ein Dokument mit zwei Ständen (Snapshot-Vertrag, ADR 0002):
 *     <ul>
 *       <li>die <em>aktuellen Kopfdaten</em> ({@code PageHeader}) als Abfragefelder: {@code sourceId}, {@code pageId},
 *           {@code spaceKey}, {@code parentId}, {@code version}, {@code metadataHash}, {@code attributesHash} exakt
 *           mit DocValues, {@code title} analysiert für eine spätere Volltextsuche, {@code modifiedAtNanos} als
 *           Zahl (Bereich und Sortierung); für die Titelsuche (#28) die Wörter des Titels exakt in
 *           {@value #TITLE_WORD} und die Sortierschlüssel {@value #SORT_TITLE} und {@value #SORT_PAGE_ID} in
 *           UTF-16-Reihenfolge ({@link #sortKey});</li>
 *       <li>der <em>Snapshot</em>: seine Kopfdaten nur gespeichert (Präfix {@code snapshot.}), {@code rawSha256}
 *           exakt, die Payload als binäres {@code StoredField} {@value #RAW_JSON} mit den Originalbytes. Fehlt, solange
 *           nur Kopfdaten bekannt sind.</li>
 *     </ul>
 *   </li>
 *   <li>{@code revision} – je Seite und Revision ein Verlaufseintrag ohne Payload.</li>
 *   <li>{@code space} – je {@code sourceId + spaceKey} die bestätigte Generation ({@code SyncBaseline} ohne Seiten):
 *       Nummer, Zeitpunkt, veraltete Seiten.</li>
 *   <li>{@code source} – je {@code sourceId} die Suchgeneration: ein Zähler, den jeder Commit erhöht, der Seiten oder
 *       Generationen dieser Quelle ändert, mit dem Commit-Zeitpunkt.</li>
 *   <li>{@code baseline} – je Seite einer bestätigten Generation ihr {@code BaselineEntry}, nur gespeichert und über
 *       {@value #BASELINE_OF} dem Space zugeordnet. Wird mit jeder Generation vollständig ersetzt.</li>
 * </ul>
 *
 * <p>Die Schemaversion steht in den Commit-Daten des Index. Ein Index mit anderem oder fehlendem Schema wird nicht
 * geöffnet: Er wird neu aufgebaut (Verzeichnis löschen, neu ernten), nie stillschweigend umgedeutet. Eine spätere
 * Schemaänderung bekommt eine neue Version und, falls nötig, eine ausdrückliche Migration.
 */
public final class PageCacheSchema {

    /** Schlüssel der Schemaversion in den Commit-Daten. */
    public static final String SCHEMA_KEY = "space-harvester.schema";
    /** Aktuelle Schemaversion. */
    public static final String VERSION = "page-cache/v3";

    static final String TYPE = "type";
    static final String ID = "id";
    static final String PAGE_OF_REVISION = "revisionOf";
    static final String BASELINE_OF = "baselineOf";

    // Aktuelle Kopfdaten (Abfragefelder)
    static final String SOURCE_ID = "sourceId";
    static final String PAGE_ID = "pageId";
    static final String SPACE_KEY = "spaceKey";
    static final String PARENT_ID = "parentId";
    static final String RESOURCE_ID = "resourceId";
    static final String TITLE = "title";
    static final String VERSION_FIELD = "version";
    static final String MODIFIED_AT = "modifiedAt";
    static final String MODIFIED_AT_NANOS = "modifiedAtNanos";
    static final String TITLE_WORD = "titleWord";
    static final String SORT_TITLE = "sort.title";
    static final String SORT_PAGE_ID = "sort.pageId";
    static final String URL = "url";
    static final String ATTRIBUTE_KEY = "attributeKey";
    static final String ATTRIBUTE_VALUE = "attributeValue";
    static final String METADATA_HASH = "metadataHash";
    static final String ATTRIBUTES_HASH = "attributesHash";
    static final String OBSERVED_AT = "observedAt";

    // Snapshot: Kopfdaten seiner Payload und die Payload
    static final String SNAPSHOT = "snapshot.";
    /**
     * Präfixe der Verlaufs- und Space-Dokumente: Lucene verlangt je Feldname dieselbe Feldart im ganzen Index, die
     * gleichnamigen Seitenfelder sind aber indexiert.
     */
    static final String REV = "revision.";
    static final String SPACE_DOC = "space.";
    static final String BASELINE_DOC = "baseline.";
    static final String SOURCE_DOC = "source.";
    static final String RAW_SHA256 = "rawSha256";
    static final String RAW_JSON = "rawJson";
    static final String PAYLOAD_BYTES = "payloadBytes";
    static final String MEDIA_TYPE = "mediaType";
    static final String NATIVE_FORMAT = "nativeFormat";
    static final String RETRIEVED_AT = "retrievedAt";

    // Bestätigte Generation
    static final String GENERATION = "generation";
    static final String CONFIRMED_AT = "confirmedAt";
    static final String STALE_PAGE_ID = "stalePageId";
    static final String MISSING_PAGE_ID = "missingPageId";
    /** Gesperrte Seite (ADR 0014) als {@code <Beginn der Sperre> <pageId>}; ein Instant enthält kein Leerzeichen. */
    static final String WITHHELD_PAGE = "withheldPage";

    static final String PAGE = "page";
    static final String REVISION = "revision";
    static final String SPACE = "space";
    static final String BASELINE = "baseline";
    static final String SOURCE = "source";
    /** Zurückgehaltener Stand einer gesperrten Seite (ADR 0014): nur gespeicherte Felder, kein Lesepfad. */
    static final String WITHHELD = "withheld";

    /** Gespeicherte Felder der aktuellen Kopfdaten und des Payload-Hashs, damit Listen die Bytes nicht laden. */
    static final Set<String> HEADER_FIELDS = Set.of(SOURCE_ID, PAGE_ID, SPACE_KEY, PARENT_ID, RESOURCE_ID, TITLE,
            VERSION_FIELD, MODIFIED_AT, URL, ATTRIBUTE_KEY, ATTRIBUTE_VALUE, METADATA_HASH, ATTRIBUTES_HASH,
            OBSERVED_AT, RAW_SHA256);

    /** Trennzeichen in zusammengesetzten IDs; kommt in Quellen-, Seiten- und Space-IDs nicht vor. */
    private static final char SEPARATOR = '\u001f';

    private PageCacheSchema() {
    }

    static Term pageTerm(PageKey key) {
        return new Term(ID, id(PAGE, key.sourceId(), key.pageId()));
    }

    static Term revisionTerm(PageKey key, String revision) {
        return new Term(ID, id(REVISION, key.sourceId(), key.pageId(), revision));
    }

    static Term withheldTerm(PageKey key) {
        return new Term(ID, id(WITHHELD, key.sourceId(), key.pageId()));
    }

    /**
     * Der zurückgehaltene Stand einer gesperrten Seite: alle gespeicherten Felder ihres Seitendokuments unter eigenem
     * Präfix (Lucene verlangt je Feldname gleiche Indexoptionen), darunter die Rohdaten, aber kein durchsuchbares Feld
     * außer Typ und ID. Keine Abfrage für Seiten, Titel oder Auflistungen findet ihn; er wartet nur auf das endgültige
     * Entfernen nach Ablauf der Frist.
     */
    static Document withheld(PageKey key, Document page) {
        Document doc = new Document();
        doc.add(new StringField(TYPE, WITHHELD, Field.Store.NO));
        doc.add(new StringField(ID, withheldTerm(key).text(), Field.Store.NO));
        for (IndexableField field : page.getFields()) {
            String name = WITHHELD + "." + field.name();
            if (field.binaryValue() != null) {
                doc.add(new StoredField(name, field.binaryValue()));
            } else if (field.stringValue() != null) {
                doc.add(new StoredField(name, field.stringValue()));
            } else if (field.numericValue() != null) {
                doc.add(new StoredField(name, field.numericValue().longValue()));
            }
        }
        return doc;
    }

    /** Alle Verlaufseinträge einer Seite. */
    static Term revisionsOfPage(PageKey key) {
        return new Term(PAGE_OF_REVISION, id(PAGE, key.sourceId(), key.pageId()));
    }

    static Term spaceTerm(String sourceId, String spaceKey) {
        return new Term(ID, id(SPACE, sourceId, spaceKey));
    }

    static Term sourceTerm(String sourceId) {
        return new Term(ID, id(SOURCE, sourceId));
    }

    /**
     * Alle Dokumente einer Quelle außer ihrer Suchgeneration (#188): Seiten, Verlauf, bestätigte Generationen und ihre
     * Seiteneinträge, zurückgehaltene Stände gesperrter Seiten.
     */
    static Query[] ofSource(String sourceId) {
        String prefix = SEPARATOR + sourceId + SEPARATOR;
        return new Query[]{new PrefixQuery(new Term(ID, PAGE + prefix)), new PrefixQuery(new Term(ID, REVISION + prefix)),
                new PrefixQuery(new Term(ID, SPACE + prefix)), new PrefixQuery(new Term(BASELINE_OF, SPACE + prefix)),
                new PrefixQuery(new Term(ID, WITHHELD + prefix))};
    }

    /** Alle Seiteneinträge der bestätigten Generation eines Space. */
    static Term baselineOf(String sourceId, String spaceKey) {
        return new Term(BASELINE_OF, id(SPACE, sourceId, spaceKey));
    }

    /**
     * Das Seitendokument aus aktuellen Kopfdaten und gespeichertem Snapshot.
     *
     * @param snapshot der Snapshot der Seite oder {@code null}, wenn noch keine Payload vorliegt
     */
    static Document page(PageHeader header, PageSnapshot snapshot) {
        PageKey key = header.key();
        PageMetadata current = header.metadata();
        Document doc = new Document();
        doc.add(new StringField(TYPE, PAGE, Field.Store.NO));
        doc.add(new StringField(ID, pageTerm(key).text(), Field.Store.NO));
        doc.add(new KeywordField(SOURCE_ID, key.sourceId(), Field.Store.YES));
        doc.add(new KeywordField(PAGE_ID, key.pageId(), Field.Store.YES));
        doc.add(new KeywordField(SPACE_KEY, current.partitionKey(), Field.Store.YES));
        if (current.parentPageId() != null) {
            doc.add(new KeywordField(PARENT_ID, current.parentPageId(), Field.Store.YES));
        }
        doc.add(new TextField(TITLE, current.title(), Field.Store.YES));
        if (current.version() != null) {
            doc.add(new KeywordField(VERSION_FIELD, current.version(), Field.Store.YES));
        }
        if (current.modifiedAt() != null) {
            doc.add(new LongField(MODIFIED_AT_NANOS, nanos(current.modifiedAt()), Field.Store.NO));
        }
        for (String word : new TreeSet<>(PageSearchQuery.words(current.title()))) {
            doc.add(new StringField(TITLE_WORD, word, Field.Store.NO));
        }
        doc.add(new SortedDocValuesField(SORT_TITLE, sortKey(current.title().toLowerCase(Locale.ROOT))));
        doc.add(new SortedDocValuesField(SORT_PAGE_ID, sortKey(key.pageId())));
        storeMetadataDetails(doc, "", current);
        doc.add(new KeywordField(METADATA_HASH, header.metadataHash(), Field.Store.YES));
        doc.add(new KeywordField(ATTRIBUTES_HASH, header.attributesHash(), Field.Store.YES));
        doc.add(new StoredField(OBSERVED_AT, header.observedAt().toString()));

        if (snapshot != null) {
            if (!snapshot.key().equals(key)) {
                throw new IllegalArgumentException("Kopfdaten und Snapshot gehören zu verschiedenen Seiten");
            }
            PageMetadata stored = snapshot.metadata();
            NativePayload payload = snapshot.payload();
            doc.add(new StoredField(SNAPSHOT + SPACE_KEY, stored.partitionKey()));
            if (stored.parentPageId() != null) {
                doc.add(new StoredField(SNAPSHOT + PARENT_ID, stored.parentPageId()));
            }
            doc.add(new StoredField(SNAPSHOT + TITLE, stored.title()));
            if (stored.version() != null) {
                doc.add(new StoredField(SNAPSHOT + VERSION_FIELD, stored.version()));
            }
            storeMetadataDetails(doc, SNAPSHOT, stored);
            doc.add(new StoredField(SNAPSHOT + METADATA_HASH, snapshot.metadataHash()));
            doc.add(new StoredField(SNAPSHOT + ATTRIBUTES_HASH, snapshot.attributesHash()));
            doc.add(new KeywordField(RAW_SHA256, payload.rawSha256(), Field.Store.YES));
            doc.add(new StoredField(PAYLOAD_BYTES, payload.length()));
            doc.add(new StoredField(MEDIA_TYPE, payload.mediaType()));
            doc.add(new StoredField(NATIVE_FORMAT, payload.nativeFormat()));
            doc.add(new StoredField(RETRIEVED_AT, snapshot.retrievedAt().toString()));
            doc.add(new StoredField(RAW_JSON, payload.bytes()));
        }
        return doc;
    }

    /** Nur gespeicherte Kopfdatenfelder, die beide Stände gleich ablegen. */
    private static void storeMetadataDetails(Document doc, String prefix, PageMetadata metadata) {
        doc.add(new StoredField(prefix + RESOURCE_ID, metadata.resourceId()));
        if (metadata.modifiedAt() != null) {
            doc.add(new StoredField(prefix + MODIFIED_AT, metadata.modifiedAt().toString()));
        }
        if (metadata.url() != null) {
            doc.add(new StoredField(prefix + URL, metadata.url().toString()));
        }
        for (Map.Entry<String, String> attribute : metadata.attributes().entrySet()) {
            doc.add(new StoredField(prefix + ATTRIBUTE_KEY, attribute.getKey()));
            doc.add(new StoredField(prefix + ATTRIBUTE_VALUE, attribute.getValue()));
        }
    }

    static Document revision(PageSnapshot snapshot) {
        PageKey key = snapshot.key();
        String revision = revisionOf(snapshot);
        Document doc = new Document();
        doc.add(new StringField(TYPE, REVISION, Field.Store.NO));
        doc.add(new StringField(ID, revisionTerm(key, revision).text(), Field.Store.NO));
        doc.add(new StringField(PAGE_OF_REVISION, revisionsOfPage(key).text(), Field.Store.NO));
        doc.add(new StoredField(REV + VERSION_FIELD, revision));
        doc.add(new StoredField(REV + RAW_SHA256, snapshot.payload().rawSha256()));
        doc.add(new StoredField(REV + METADATA_HASH, snapshot.metadataHash()));
        doc.add(new StoredField(REV + ATTRIBUTES_HASH, snapshot.attributesHash()));
        doc.add(new StoredField(REV + RETRIEVED_AT, snapshot.retrievedAt().toString()));
        return doc;
    }

    /** Kopf der bestätigten Generation eines Space; die Seiteneinträge liefert {@link #baselineEntry}. */
    static Document space(SyncBaseline baseline) {
        Document doc = new Document();
        doc.add(new StringField(TYPE, SPACE, Field.Store.NO));
        doc.add(new StringField(ID, spaceTerm(baseline.sourceId(), baseline.spaceKey()).text(), Field.Store.NO));
        doc.add(new StoredField(SPACE_DOC + SOURCE_ID, baseline.sourceId()));
        doc.add(new StoredField(SPACE_DOC + SPACE_KEY, baseline.spaceKey()));
        doc.add(new StoredField(GENERATION, baseline.generation()));
        doc.add(new StoredField(CONFIRMED_AT, baseline.confirmedAt().toString()));
        for (String pageId : baseline.stalePageIds()) {
            doc.add(new StoredField(STALE_PAGE_ID, pageId));
        }
        for (String pageId : baseline.missingPageIds()) {
            doc.add(new StoredField(MISSING_PAGE_ID, pageId));
        }
        baseline.withheldPageIds().forEach((pageId, since) ->
                doc.add(new StoredField(WITHHELD_PAGE, since + " " + pageId)));
        return doc;
    }

    static Document baselineEntry(String sourceId, String spaceKey, BaselineEntry entry) {
        Document doc = new Document();
        doc.add(new StringField(TYPE, BASELINE, Field.Store.NO));
        doc.add(new StringField(BASELINE_OF, baselineOf(sourceId, spaceKey).text(), Field.Store.NO));
        doc.add(new StoredField(BASELINE_DOC + PAGE_ID, entry.pageId()));
        doc.add(new StoredField(BASELINE_DOC + SPACE_KEY, entry.spaceKey()));
        if (entry.parentPageId() != null) {
            doc.add(new StoredField(BASELINE_DOC + PARENT_ID, entry.parentPageId()));
        }
        if (entry.version() != null) {
            doc.add(new StoredField(BASELINE_DOC + VERSION_FIELD, entry.version()));
        }
        doc.add(new StoredField(BASELINE_DOC + METADATA_HASH, entry.metadataHash()));
        doc.add(new StoredField(BASELINE_DOC + ATTRIBUTES_HASH, entry.attributesHash()));
        return doc;
    }

    /** Suchgeneration einer Quelle; {@code generation} zählt ab 1. */
    static Document source(String sourceId, long generation, Instant committedAt) {
        Document doc = new Document();
        doc.add(new StringField(TYPE, SOURCE, Field.Store.NO));
        doc.add(new StringField(ID, sourceTerm(sourceId).text(), Field.Store.NO));
        doc.add(new StoredField(SOURCE_DOC + GENERATION, generation));
        doc.add(new StoredField(SOURCE_DOC + CONFIRMED_AT, committedAt.toString()));
        return doc;
    }

    static long sourceGeneration(Document source) {
        return number(source, SOURCE_DOC + GENERATION);
    }

    static Instant sourceCommittedAt(Document source) {
        return instant(source.get(SOURCE_DOC + CONFIRMED_AT));
    }

    /**
     * Sortierschlüssel, dessen Byte-Reihenfolge der von {@link String#compareTo} entspricht: die UTF-16-Codeeinheiten
     * als Big-Endian-Bytes. (UTF-8 sortiert Zeichen außerhalb der BMP anders.)
     */
    static BytesRef sortKey(String text) {
        byte[] bytes = new byte[text.length() * 2];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            bytes[2 * i] = (byte) (c >>> 8);
            bytes[2 * i + 1] = (byte) c;
        }
        return new BytesRef(bytes);
    }

    /** Nanosekunden seit der Epoche, außerhalb von etwa 1677 bis 2262 auf den Rand begrenzt. */
    static long nanos(Instant instant) {
        try {
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
        } catch (ArithmeticException e) {
            return instant.getEpochSecond() < 0 ? Long.MIN_VALUE + 1 : Long.MAX_VALUE;
        }
    }

    static String revisionOf(PageSnapshot snapshot) {
        return snapshot.revision() == null ? "" : snapshot.revision();
    }

    /** Die aktuellen Kopfdaten eines Seitendokuments. */
    static PageHeader header(Document doc) {
        PageKey key = new PageKey(doc.get(SOURCE_ID), doc.get(PAGE_ID));
        PageMetadata metadata = metadata(doc, "", key.pageId(), doc.get(TITLE), doc.get(SPACE_KEY),
                doc.get(PARENT_ID), doc.get(VERSION_FIELD));
        return new PageHeader(key, metadata, doc.get(METADATA_HASH), doc.get(ATTRIBUTES_HASH),
                instant(doc.get(OBSERVED_AT)));
    }

    /** Ob das Seitendokument einen Snapshot enthält. */
    static boolean hasSnapshot(Document doc) {
        return doc.get(RAW_SHA256) != null;
    }

    /** Der gespeicherte Snapshot; prüft, dass die gespeicherten Bytes noch zu ihrem Hash passen. */
    static PageSnapshot snapshot(Document doc) {
        PageKey key = new PageKey(doc.get(SOURCE_ID), doc.get(PAGE_ID));
        BytesRef raw = doc.getBinaryValue(RAW_JSON);
        String rawSha256 = doc.get(RAW_SHA256);
        if (raw == null || rawSha256 == null) {
            throw new PageCacheException("Payload fehlt im Cache: " + key);
        }
        NativePayload payload = new NativePayload(Arrays.copyOfRange(raw.bytes, raw.offset, raw.offset + raw.length),
                doc.get(MEDIA_TYPE), doc.get(NATIVE_FORMAT));
        if (!payload.rawSha256().equals(rawSha256)) {
            throw new PageCacheException("Payload im Cache passt nicht zu ihrem rawSha256 (beschädigt): " + key);
        }
        PageMetadata metadata = metadata(doc, SNAPSHOT, key.pageId(), doc.get(SNAPSHOT + TITLE),
                doc.get(SNAPSHOT + SPACE_KEY), doc.get(SNAPSHOT + PARENT_ID), doc.get(SNAPSHOT + VERSION_FIELD));
        return new PageSnapshot(key, metadata, doc.get(SNAPSHOT + METADATA_HASH), doc.get(SNAPSHOT + ATTRIBUTES_HASH),
                payload, instant(doc.get(RETRIEVED_AT)));
    }

    private static PageMetadata metadata(Document doc, String prefix, String pageId, String title, String spaceKey,
                                         String parentId, String version) {
        String[] keys = doc.getValues(prefix + ATTRIBUTE_KEY);
        String[] values = doc.getValues(prefix + ATTRIBUTE_VALUE);
        if (keys.length != values.length) {
            throw new PageCacheException("Attribute einer Seite im Cache sind beschädigt");
        }
        SortedMap<String, String> attributes = new TreeMap<>();
        for (int i = 0; i < keys.length; i++) {
            attributes.put(keys[i], values[i]);
        }
        String url = doc.get(prefix + URL);
        return new PageMetadata(pageId, doc.get(prefix + RESOURCE_ID), title, spaceKey, parentId, version,
                instant(doc.get(prefix + MODIFIED_AT)), url == null ? null : URI.create(url), attributes);
    }

    static CachedRevision cachedRevision(Document doc) {
        return new CachedRevision(doc.get(REV + VERSION_FIELD), doc.get(REV + RAW_SHA256),
                doc.get(REV + METADATA_HASH), doc.get(REV + ATTRIBUTES_HASH), instant(doc.get(REV + RETRIEVED_AT)));
    }

    static long generation(Document space) {
        return number(space, GENERATION);
    }

    static ConfirmedGeneration confirmedGeneration(Document space) {
        return new ConfirmedGeneration(space.get(SPACE_DOC + SOURCE_ID), space.get(SPACE_DOC + SPACE_KEY),
                generation(space), instant(space.get(CONFIRMED_AT)), space.getValues(STALE_PAGE_ID).length,
                space.getValues(MISSING_PAGE_ID).length);
    }

    /** Die bestätigte Generation aus dem Space-Dokument und den Dokumenten ihrer Seiten. */
    static SyncBaseline baseline(Document space, List<Document> entries) {
        SortedMap<String, BaselineEntry> pages = new TreeMap<>();
        for (Document doc : entries) {
            BaselineEntry entry = new BaselineEntry(doc.get(BASELINE_DOC + PAGE_ID), doc.get(BASELINE_DOC + SPACE_KEY),
                    doc.get(BASELINE_DOC + PARENT_ID), doc.get(BASELINE_DOC + VERSION_FIELD),
                    doc.get(BASELINE_DOC + METADATA_HASH), doc.get(BASELINE_DOC + ATTRIBUTES_HASH));
            pages.put(entry.pageId(), entry);
        }
        return new SyncBaseline(space.get(SPACE_DOC + SOURCE_ID), space.get(SPACE_DOC + SPACE_KEY), generation(space),
                instant(space.get(CONFIRMED_AT)), pages, new TreeSet<>(List.of(space.getValues(STALE_PAGE_ID))),
                new TreeSet<>(List.of(space.getValues(MISSING_PAGE_ID))), withheld(space));
    }

    private static SortedMap<String, Instant> withheld(Document space) {
        SortedMap<String, Instant> withheld = new TreeMap<>();
        for (String value : space.getValues(WITHHELD_PAGE)) {
            int separator = value.indexOf(' ');
            withheld.put(value.substring(separator + 1), instant(value.substring(0, separator)));
        }
        return withheld;
    }

    private static long number(Document doc, String field) {
        IndexableField value = doc.getField(field);
        if (value == null || value.numericValue() == null) {
            throw new PageCacheException("Feld " + field + " fehlt im Cache");
        }
        return value.numericValue().longValue();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String id(String type, String... parts) {
        StringBuilder id = new StringBuilder(type);
        for (String part : parts) {
            if (part.indexOf(SEPARATOR) >= 0) {
                throw new IllegalArgumentException("ID enthält ein unzulässiges Steuerzeichen");
            }
            id.append(SEPARATOR).append(part);
        }
        return id.toString();
    }
}
