package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import java.time.Instant;
import java.util.TreeSet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * Zeilen des Änderungsprotokolls (ADR 0030) im Index des Seiten-Cache: eine eigene Dokumentart mit eigenen Feldnamen,
 * damit keine Abfrage auf Seiten, Verlauf, Generationen oder Zähler sie je trifft. Kein neues Schema: vorhandene
 * Caches bleiben lesbar, nichts wird neu aufgebaut.
 */
final class ChangeLogSchema {

    /** Wert von {@link PageCacheSchema#TYPE} und Präfix der IDs. */
    static final String CHANGE = "change";
    private static final String P = CHANGE + ".";
    static final String SOURCE_ID = P + "sourceId";
    static final String SPACE_KEY = P + "spaceKey";
    static final String PAGE_ID = P + "pageId";
    static final String AT = P + "at";
    static final String KIND = P + "kind";
    static final String TRIGGER = P + "trigger";
    static final String TITLE_WORD = P + "titleWord";
    private static final String AT_TEXT = P + "atText";
    private static final String OLD_VERSION = P + "oldVersion";
    private static final String NEW_VERSION = P + "newVersion";
    private static final String OLD_TITLE = P + "oldTitle";
    private static final String NEW_TITLE = P + "newTitle";
    private static final String OLD_PARENT = P + "oldParentId";
    private static final String NEW_PARENT = P + "newParentId";
    private static final String OLD_SPACE = P + "oldSpaceKey";
    private static final String NEW_SPACE = P + "newSpaceKey";
    private static final String GENERATION = P + "generation";
    private static final String PENDING = P + "contentPending";

    private ChangeLogSchema() {
    }

    /** Alle Protokollzeilen. */
    static Query all() {
        return new TermQuery(new Term(PageCacheSchema.TYPE, CHANGE));
    }

    /** Alle Protokollzeilen einer Quelle, für das Entfernen der Quelle (über die ID wie die übrigen Dokumente). */
    static Query ofSource(String sourceId) {
        return new PrefixQuery(new Term(PageCacheSchema.ID, PageCacheSchema.id(CHANGE, sourceId) + '\u001f'));
    }

    /** Protokollzeilen vor {@code cutoff}. */
    static Query before(Instant cutoff) {
        return new BooleanQuery.Builder().add(all(), BooleanClause.Occur.FILTER)
                .add(LongField.newRangeQuery(AT, Long.MIN_VALUE, PageCacheSchema.nanos(cutoff) - 1),
                        BooleanClause.Occur.FILTER)
                .build();
    }

    static Document change(ChangeEntry entry) {
        Document doc = new Document();
        doc.add(new StringField(PageCacheSchema.TYPE, CHANGE, Field.Store.NO));
        doc.add(new StringField(PageCacheSchema.ID, PageCacheSchema.id(CHANGE, entry.sourceId(), entry.pageId(),
                Long.toString(PageCacheSchema.nanos(entry.at())), entry.kind().name()), Field.Store.NO));
        doc.add(new StringField(SOURCE_ID, entry.sourceId(), Field.Store.YES));
        doc.add(new StringField(SPACE_KEY, entry.spaceKey(), Field.Store.YES));
        doc.add(new StringField(PAGE_ID, entry.pageId(), Field.Store.YES));
        doc.add(new LongField(AT, PageCacheSchema.nanos(entry.at()), Field.Store.NO));
        doc.add(new StoredField(AT_TEXT, entry.at().toString()));
        doc.add(new StringField(KIND, entry.kind().name(), Field.Store.YES));
        doc.add(new StringField(TRIGGER, entry.trigger().name(), Field.Store.YES));
        TreeSet<String> words = new TreeSet<>();
        if (entry.oldTitle() != null) {
            words.addAll(PageSearchQuery.words(entry.oldTitle()));
        }
        if (entry.newTitle() != null) {
            words.addAll(PageSearchQuery.words(entry.newTitle()));
        }
        for (String word : words) {
            doc.add(new StringField(TITLE_WORD, word, Field.Store.NO));
        }
        stored(doc, OLD_VERSION, entry.oldVersion());
        stored(doc, NEW_VERSION, entry.newVersion());
        stored(doc, OLD_TITLE, entry.oldTitle());
        stored(doc, NEW_TITLE, entry.newTitle());
        stored(doc, OLD_PARENT, entry.oldParentId());
        stored(doc, NEW_PARENT, entry.newParentId());
        stored(doc, OLD_SPACE, entry.oldSpaceKey());
        stored(doc, NEW_SPACE, entry.newSpaceKey());
        if (entry.generation() != null) {
            doc.add(new StoredField(GENERATION, entry.generation()));
        }
        if (entry.contentPending()) {
            doc.add(new StoredField(PENDING, "true"));
        }
        return doc;
    }

    static ChangeEntry entry(Document doc) {
        Number generation = doc.getField(GENERATION) == null ? null : doc.getField(GENERATION).numericValue();
        return new ChangeEntry(doc.get(SOURCE_ID), doc.get(SPACE_KEY), doc.get(PAGE_ID), Instant.parse(doc.get(AT_TEXT)),
                ChangeKind.valueOf(doc.get(KIND)), ChangeTrigger.parse(doc.get(TRIGGER)), doc.get(OLD_VERSION),
                doc.get(NEW_VERSION), doc.get(OLD_TITLE), doc.get(NEW_TITLE), doc.get(OLD_PARENT),
                doc.get(NEW_PARENT), doc.get(OLD_SPACE), doc.get(NEW_SPACE),
                generation == null ? null : generation.longValue(), doc.get(PENDING) != null);
    }

    private static void stored(Document doc, String field, String value) {
        if (value != null) {
            doc.add(new StoredField(field, value));
        }
    }
}
