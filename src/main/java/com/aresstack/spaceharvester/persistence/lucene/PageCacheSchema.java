package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KeywordField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
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
 *           mit DocValues, {@code title} analysiert für die spätere Titelsuche (#28), {@code modifiedAtMillis} als
 *           Zahl;</li>
 *       <li>der <em>Snapshot</em>: seine Kopfdaten nur gespeichert (Präfix {@code snapshot.}), {@code rawSha256}
 *           exakt, die Payload als binäres {@code StoredField} {@value #RAW_JSON} mit den Originalbytes. Fehlt, solange
 *           nur Kopfdaten bekannt sind.</li>
 *     </ul>
 *   </li>
 *   <li>{@code revision} – je Seite und Revision ein Verlaufseintrag ohne Payload.</li>
 *   <li>{@code space} – je {@code sourceId + spaceKey} der Synchronisierungsstand ({@link SpaceSyncState}).</li>
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
    public static final String VERSION = "page-cache/v1";

    static final String TYPE = "type";
    static final String ID = "id";
    static final String PAGE_OF_REVISION = "revisionOf";

    // Aktuelle Kopfdaten (Abfragefelder)
    static final String SOURCE_ID = "sourceId";
    static final String PAGE_ID = "pageId";
    static final String SPACE_KEY = "spaceKey";
    static final String PARENT_ID = "parentId";
    static final String RESOURCE_ID = "resourceId";
    static final String TITLE = "title";
    static final String VERSION_FIELD = "version";
    static final String MODIFIED_AT = "modifiedAt";
    static final String MODIFIED_AT_MILLIS = "modifiedAtMillis";
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
    static final String RAW_SHA256 = "rawSha256";
    static final String RAW_JSON = "rawJson";
    static final String PAYLOAD_BYTES = "payloadBytes";
    static final String MEDIA_TYPE = "mediaType";
    static final String NATIVE_FORMAT = "nativeFormat";
    static final String RETRIEVED_AT = "retrievedAt";

    // Space-Stand
    static final String GENERATION = "generation";
    static final String LAST_RUN_COMPLETE = "lastRunComplete";
    static final String LAST_RUN_AT = "lastRunAt";
    static final String CONFIRMED_GENERATION = "confirmedGeneration";
    static final String CONFIRMED_AT = "confirmedAt";
    static final String CONFIRMED_PAGE_COUNT = "confirmedPageCount";

    static final String PAGE = "page";
    static final String REVISION = "revision";
    static final String SPACE = "space";

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

    /** Alle Verlaufseinträge einer Seite. */
    static Term revisionsOfPage(PageKey key) {
        return new Term(PAGE_OF_REVISION, id(PAGE, key.sourceId(), key.pageId()));
    }

    static Term spaceTerm(String sourceId, String spaceKey) {
        return new Term(ID, id(SPACE, sourceId, spaceKey));
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
            doc.add(new LongField(MODIFIED_AT_MILLIS, current.modifiedAt().toEpochMilli(), Field.Store.NO));
        }
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

    static Document space(SpaceSyncState state) {
        Document doc = new Document();
        doc.add(new StringField(TYPE, SPACE, Field.Store.NO));
        doc.add(new StringField(ID, spaceTerm(state.sourceId(), state.spaceKey()).text(), Field.Store.NO));
        doc.add(new StoredField(SPACE_DOC + SOURCE_ID, state.sourceId()));
        doc.add(new StoredField(SPACE_DOC + SPACE_KEY, state.spaceKey()));
        doc.add(new StoredField(GENERATION, state.generation()));
        doc.add(new StoredField(LAST_RUN_COMPLETE, state.lastRunComplete() ? 1 : 0));
        doc.add(new StoredField(LAST_RUN_AT, state.lastRunAt().toString()));
        doc.add(new StoredField(CONFIRMED_GENERATION, state.confirmedGeneration()));
        if (state.confirmedAt() != null) {
            doc.add(new StoredField(CONFIRMED_AT, state.confirmedAt().toString()));
        }
        doc.add(new StoredField(CONFIRMED_PAGE_COUNT, state.confirmedPageCount()));
        return doc;
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

    static SpaceSyncState spaceState(Document doc) {
        return new SpaceSyncState(doc.get(SPACE_DOC + SOURCE_ID), doc.get(SPACE_DOC + SPACE_KEY),
                number(doc, GENERATION),
                number(doc, LAST_RUN_COMPLETE) == 1, instant(doc.get(LAST_RUN_AT)),
                number(doc, CONFIRMED_GENERATION), instant(doc.get(CONFIRMED_AT)),
                number(doc, CONFIRMED_PAGE_COUNT));
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
