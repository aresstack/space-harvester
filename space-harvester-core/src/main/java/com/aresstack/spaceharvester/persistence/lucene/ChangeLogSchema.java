package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeQuery;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.changelog.ContentChange;
import com.aresstack.spaceharvester.application.changelog.ParagraphChange;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.changelog.StoredSummary;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TermQuery;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
    /**
     * Der alte Space im Index, für den Space-Filter. Eigener Name, weil Lucene die Art eines Feldes im ganzen Index
     * festhält: {@link #OLD_SPACE} ist in bestehenden Caches nur abgelegt.
     */
    static final String OLD_SPACE_TERM = P + "oldSpaceKeyTerm";
    private static final String NEW_SPACE = P + "newSpaceKey";
    private static final String GENERATION = P + "generation";
    private static final String PENDING = P + "contentPending";
    /** Nur abgelegt, nie indiziert: Personenbezug (ADR 0031), nicht suchbar. */
    private static final String EDITOR = P + "editor";
    private static final String LABEL = P + "label";

    /** Wert von {@link PageCacheSchema#TYPE} für Update-Marken und Präfix ihrer IDs. */
    static final String MARK = "mark";
    private static final String M = MARK + ".";
    static final String MARK_SOURCE_ID = M + "sourceId";
    static final String MARK_AT = M + "at";
    private static final String MARK_AT_TEXT = M + "atText";
    private static final String MARK_USER = M + "user";
    private static final String MARK_SPACE = M + "space";
    private static final String MARK_FAILED = M + "failedSyncs";
    private static final String MARK_INITIAL = M + "initial";

    /** Wert von {@link PageCacheSchema#TYPE} für Zusammenfassungen und Präfix ihrer IDs (ADR 0030, Abschnitt 5). */
    static final String SUMMARY = "summary";
    private static final String S = SUMMARY + ".";
    static final String SUMMARY_SOURCE_ID = S + "sourceId";
    private static final String SUMMARY_UPDATE = S + "update";
    private static final String SUMMARY_AT = S + "at";
    private static final String SUMMARY_JSON = S + "json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Wert von {@link PageCacheSchema#TYPE} für Unterschiede beim Ersetzen eines Inhalts und Präfix ihrer IDs. */
    static final String DIFF = "diff";
    private static final String X = DIFF + ".";
    static final String DIFF_SOURCE_ID = X + "sourceId";
    static final String DIFF_PAGE_ID = X + "pageId";
    static final String DIFF_AT = X + "at";
    private static final String DIFF_AT_TEXT = X + "atText";
    private static final String DIFF_FROM = X + "fromVersion";
    private static final String DIFF_TO = X + "toVersion";
    private static final String DIFF_PART = X + "part";
    private static final String DIFF_TRUNCATED = X + "truncated";
    private static final char SEPARATOR = '\u001f';
    private static final char HEADING_SEPARATOR = '\u001e';

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

    /**
     * Die Abfrage zu {@code query}: Quellen, Arten, Auslöser, Space (neu oder alt), Suchtext und Zeitraum liegen im
     * Index, nicht im Speicher. Zeilen vor dieser Fassung haben den alten Space nicht im Index; der Space-Filter trifft
     * sie nur über den neuen.
     */
    static Query query(ChangeQuery query) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder().add(all(), BooleanClause.Occur.FILTER)
                .add(anyOf(SOURCE_ID, query.sourceIds()), BooleanClause.Occur.FILTER);
        if (!query.kinds().isEmpty()) {
            builder.add(anyOf(KIND, query.kinds().stream().map(Enum::name).toList()), BooleanClause.Occur.FILTER);
        }
        if (!query.triggers().isEmpty()) {
            builder.add(anyOf(TRIGGER, query.triggers().stream().map(Enum::name).toList()),
                    BooleanClause.Occur.FILTER);
        }
        if (query.spaceKey() != null) {
            builder.add(new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(SPACE_KEY, query.spaceKey())), BooleanClause.Occur.SHOULD)
                    .add(new TermQuery(new Term(OLD_SPACE_TERM, query.spaceKey())), BooleanClause.Occur.SHOULD)
                    .build(), BooleanClause.Occur.FILTER);
        }
        List<String> words = query.words();
        if (query.text() != null && !words.isEmpty()) {
            BooleanQuery.Builder title = new BooleanQuery.Builder();
            for (String word : words) {
                title.add(new PrefixQuery(new Term(TITLE_WORD, word)), BooleanClause.Occur.FILTER);
            }
            builder.add(new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(PAGE_ID, query.text())), BooleanClause.Occur.SHOULD)
                    .add(title.build(), BooleanClause.Occur.SHOULD)
                    .build(), BooleanClause.Occur.FILTER);
        }
        if (query.lower() != null || query.upper() != null) {
            long lower = query.lower() == null ? Long.MIN_VALUE
                    : PageCacheSchema.nanos(query.lower()) + (query.lowerInclusive() ? 0 : 1);
            long upper = query.upper() == null ? Long.MAX_VALUE
                    : PageCacheSchema.nanos(query.upper()) - (query.upperInclusive() ? 0 : 1);
            builder.add(LongField.newRangeQuery(AT, lower, upper), BooleanClause.Occur.FILTER);
        }
        return builder.build();
    }

    /** Nach Zeitpunkt, gleiche Zeitpunkte in der Reihenfolge des Schreibens. */
    static Sort order(ChangeQuery query) {
        return new Sort(new SortedNumericSortField(AT, SortField.Type.LONG, query.newestFirst()),
                SortField.FIELD_DOC);
    }

    private static Query anyOf(String field, Collection<String> values) {
        if (values.size() == 1) {
            return new TermQuery(new Term(field, values.iterator().next()));
        }
        BooleanQuery.Builder any = new BooleanQuery.Builder();
        for (String value : values) {
            any.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.SHOULD);
        }
        return any.build();
    }

    /** Alle Unterschiede. */
    static Query allDiffs() {
        return new TermQuery(new Term(PageCacheSchema.TYPE, DIFF));
    }

    /** Die Unterschiede einer Seite nach {@code after} (ausschließlich, {@code null} ohne Grenze). */
    static Query diffsOf(PageKey key, Instant after) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder().add(allDiffs(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(DIFF_SOURCE_ID, key.sourceId())), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(DIFF_PAGE_ID, key.pageId())), BooleanClause.Occur.FILTER);
        if (after != null) {
            builder.add(LongField.newRangeQuery(DIFF_AT, PageCacheSchema.nanos(after) + 1, Long.MAX_VALUE),
                    BooleanClause.Occur.FILTER);
        }
        return builder.build();
    }

    /** Nach Zeitpunkt aufsteigend. */
    static Sort diffOrder() {
        return new Sort(new SortedNumericSortField(DIFF_AT, SortField.Type.LONG), SortField.FIELD_DOC);
    }

    /** Die Unterschiede einer Quelle über die ID, für das Entfernen der Quelle. */
    static Query diffsOfSource(String sourceId) {
        return new PrefixQuery(new Term(PageCacheSchema.ID, PageCacheSchema.id(DIFF, sourceId) + '\u001f'));
    }

    /** Unterschiede vor {@code cutoff}: Sie werden so lange aufbewahrt wie die Zeilen. */
    static Query diffsBefore(Instant cutoff) {
        return new BooleanQuery.Builder().add(allDiffs(), BooleanClause.Occur.FILTER)
                .add(LongField.newRangeQuery(DIFF_AT, Long.MIN_VALUE, PageCacheSchema.nanos(cutoff) - 1),
                        BooleanClause.Occur.FILTER)
                .build();
    }

    static Document diff(ContentChange change) {
        Document doc = new Document();
        doc.add(new StringField(PageCacheSchema.TYPE, DIFF, Field.Store.NO));
        doc.add(new StringField(PageCacheSchema.ID, PageCacheSchema.id(DIFF, change.sourceId(), change.pageId(),
                Long.toString(PageCacheSchema.nanos(change.at()))), Field.Store.NO));
        doc.add(new StringField(DIFF_SOURCE_ID, change.sourceId(), Field.Store.YES));
        doc.add(new StringField(DIFF_PAGE_ID, change.pageId(), Field.Store.YES));
        doc.add(new LongField(DIFF_AT, PageCacheSchema.nanos(change.at()), Field.Store.NO));
        doc.add(new StoredField(DIFF_AT_TEXT, change.at().toString()));
        stored(doc, DIFF_FROM, change.fromVersion());
        stored(doc, DIFF_TO, change.toVersion());
        for (ParagraphChange paragraph : change.paragraphs()) {
            doc.add(new StoredField(DIFF_PART, paragraph.kind().name() + SEPARATOR
                    + (paragraph.before() == null ? "" : paragraph.before()) + SEPARATOR
                    + (paragraph.after() == null ? "" : paragraph.after()) + SEPARATOR
                    + String.join(String.valueOf(HEADING_SEPARATOR), paragraph.headings())));
        }
        if (change.truncated()) {
            doc.add(new StoredField(DIFF_TRUNCATED, "true"));
        }
        return doc;
    }

    static ContentChange diff(Document doc) {
        List<ParagraphChange> paragraphs = new java.util.ArrayList<>();
        for (String part : doc.getValues(DIFF_PART)) {
            int first = part.indexOf(SEPARATOR);
            int second = part.indexOf(SEPARATOR, first + 1);
            ParagraphChange.Kind kind = ParagraphChange.Kind.valueOf(part.substring(0, first));
            String before = part.substring(first + 1, second);
            // Vor ADR 0031 abgelegte Absätze haben keinen Überschriftenpfad.
            int third = part.indexOf(SEPARATOR, second + 1);
            String after = third < 0 ? part.substring(second + 1) : part.substring(second + 1, third);
            List<String> headings = third < 0 || third == part.length() - 1 ? List.of()
                    : List.of(part.substring(third + 1).split(String.valueOf(HEADING_SEPARATOR), -1));
            paragraphs.add(new ParagraphChange(kind, kind == ParagraphChange.Kind.ADDED ? null : before,
                    kind == ParagraphChange.Kind.REMOVED ? null : after, headings));
        }
        return new ContentChange(doc.get(DIFF_SOURCE_ID), doc.get(DIFF_PAGE_ID), doc.get(DIFF_FROM), doc.get(DIFF_TO),
                Instant.parse(doc.get(DIFF_AT_TEXT)), paragraphs, doc.get(DIFF_TRUNCATED) != null);
    }

    /** Alle Update-Marken. */
    static Query allMarks() {
        return new TermQuery(new Term(PageCacheSchema.TYPE, MARK));
    }

    /** Die Update-Marken einer Quelle. */
    static Query marksOf(String sourceId) {
        return new BooleanQuery.Builder().add(allMarks(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(MARK_SOURCE_ID, sourceId)), BooleanClause.Occur.FILTER)
                .build();
    }

    /** Die Update-Marken einer Quelle über die ID, für das Entfernen der Quelle. */
    static Query marksOfSource(String sourceId) {
        return new PrefixQuery(new Term(PageCacheSchema.ID, PageCacheSchema.id(MARK, sourceId) + '\u001f'));
    }

    /** Update-Marken vor {@code cutoff}. */
    static Query marksBefore(Instant cutoff) {
        return new BooleanQuery.Builder().add(allMarks(), BooleanClause.Occur.FILTER)
                .add(LongField.newRangeQuery(MARK_AT, Long.MIN_VALUE, PageCacheSchema.nanos(cutoff) - 1),
                        BooleanClause.Occur.FILTER)
                .build();
    }

    /** Eindeutige ID einer Marke: Quelle und Zeitpunkt. */
    static Term markTerm(UpdateMark mark) {
        return new Term(PageCacheSchema.ID, PageCacheSchema.id(MARK, mark.sourceId(),
                Long.toString(PageCacheSchema.nanos(mark.at()))));
    }

    static Document mark(UpdateMark mark) {
        Document doc = new Document();
        doc.add(new StringField(PageCacheSchema.TYPE, MARK, Field.Store.NO));
        doc.add(new StringField(PageCacheSchema.ID, markTerm(mark).text(), Field.Store.NO));
        doc.add(new StringField(MARK_SOURCE_ID, mark.sourceId(), Field.Store.YES));
        doc.add(new LongField(MARK_AT, PageCacheSchema.nanos(mark.at()), Field.Store.NO));
        doc.add(new StoredField(MARK_AT_TEXT, mark.at().toString()));
        stored(doc, MARK_USER, mark.user());
        for (String space : mark.spaces()) {
            doc.add(new StoredField(MARK_SPACE, space));
        }
        doc.add(new StoredField(MARK_FAILED, mark.failedSyncs()));
        if (mark.initial()) {
            doc.add(new StoredField(MARK_INITIAL, "true"));
        }
        return doc;
    }

    static UpdateMark mark(Document doc) {
        Number failed = doc.getField(MARK_FAILED) == null ? null : doc.getField(MARK_FAILED).numericValue();
        return new UpdateMark(doc.get(MARK_SOURCE_ID), Instant.parse(doc.get(MARK_AT_TEXT)), doc.get(MARK_USER),
                Arrays.asList(doc.getValues(MARK_SPACE)), failed == null ? 0 : failed.intValue(),
                doc.get(MARK_INITIAL) != null);
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
        if (entry.oldSpaceKey() != null) {
            doc.add(new StringField(OLD_SPACE_TERM, entry.oldSpaceKey(), Field.Store.NO));
        }
        stored(doc, NEW_SPACE, entry.newSpaceKey());
        if (entry.generation() != null) {
            doc.add(new StoredField(GENERATION, entry.generation()));
        }
        if (entry.contentPending()) {
            doc.add(new StoredField(PENDING, "true"));
        }
        stored(doc, EDITOR, entry.editor());
        for (String label : entry.labels()) {
            doc.add(new StoredField(LABEL, label));
        }
        return doc;
    }

    static ChangeEntry entry(Document doc) {
        Number generation = doc.getField(GENERATION) == null ? null : doc.getField(GENERATION).numericValue();
        return new ChangeEntry(doc.get(SOURCE_ID), doc.get(SPACE_KEY), doc.get(PAGE_ID), Instant.parse(doc.get(AT_TEXT)),
                ChangeKind.valueOf(doc.get(KIND)), ChangeTrigger.parse(doc.get(TRIGGER)), doc.get(OLD_VERSION),
                doc.get(NEW_VERSION), doc.get(OLD_TITLE), doc.get(NEW_TITLE), doc.get(OLD_PARENT),
                doc.get(NEW_PARENT), doc.get(OLD_SPACE), doc.get(NEW_SPACE),
                generation == null ? null : generation.longValue(), doc.get(PENDING) != null, doc.get(EDITOR),
                List.of(doc.getValues(LABEL)));
    }

    /** Alle Zusammenfassungen. */
    static Query allSummaries() {
        return new TermQuery(new Term(PageCacheSchema.TYPE, SUMMARY));
    }

    /** Die Zusammenfassungen einer Quelle. */
    static Query summariesOf(String sourceId) {
        return new BooleanQuery.Builder().add(allSummaries(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(SUMMARY_SOURCE_ID, sourceId)), BooleanClause.Occur.FILTER)
                .build();
    }

    /** Die Zusammenfassungen einer Quelle über die ID, für das Entfernen der Quelle. */
    static Query summariesOfSource(String sourceId) {
        return new PrefixQuery(new Term(PageCacheSchema.ID, PageCacheSchema.id(SUMMARY, sourceId) + '\u001f'));
    }

    /** Zusammenfassungen, die vor {@code cutoff} aufzubewahren endeten. */
    static Query summariesBefore(Instant cutoff) {
        return new BooleanQuery.Builder().add(allSummaries(), BooleanClause.Occur.FILTER)
                .add(LongField.newRangeQuery(SUMMARY_AT, Long.MIN_VALUE, PageCacheSchema.nanos(cutoff) - 1),
                        BooleanClause.Occur.FILTER)
                .build();
    }

    /** Eindeutige ID einer Zusammenfassung: Quelle und Update. */
    static Term summaryTerm(String sourceId, String updateId) {
        return new Term(PageCacheSchema.ID, PageCacheSchema.id(SUMMARY, sourceId, updateId));
    }

    static String summaryUpdate(Document doc) {
        return doc.get(SUMMARY_UPDATE);
    }

    static Document summary(StoredSummary summary) {
        ObjectNode json = JSON.createObjectNode();
        json.put("text", summary.text());
        ArrayNode statements = json.putArray("statements");
        for (StoredSummary.Statement statement : summary.statements()) {
            ObjectNode row = statements.addObject();
            row.put("text", statement.text());
            ArrayNode pages = row.putArray("pageIds");
            statement.pageIds().forEach(pages::add);
        }
        json.put("model", summary.model());
        json.put("createdAt", summary.createdAt().toString());
        json.put("at", summary.at().toString());
        json.put("unreadableChanges", summary.unreadableChanges());
        json.put("reportSha256", summary.reportSha256());
        ArrayNode inputs = json.putArray("inputPages");
        for (StoredSummary.InputPage page : summary.inputPages()) {
            inputs.addObject().put("spaceKey", page.spaceKey()).put("pageId", page.pageId())
                    .put("content", page.content());
        }
        Document doc = new Document();
        doc.add(new StringField(PageCacheSchema.TYPE, SUMMARY, Field.Store.NO));
        doc.add(new StringField(PageCacheSchema.ID, summaryTerm(summary.sourceId(), summary.updateId()).text(),
                Field.Store.NO));
        doc.add(new StringField(SUMMARY_SOURCE_ID, summary.sourceId(), Field.Store.YES));
        doc.add(new StoredField(SUMMARY_UPDATE, summary.updateId()));
        doc.add(new LongField(SUMMARY_AT, PageCacheSchema.nanos(summary.at()), Field.Store.NO));
        doc.add(new StoredField(SUMMARY_JSON, JSON.writeValueAsString(json)));
        return doc;
    }

    static StoredSummary summary(Document doc) {
        JsonNode json = JSON.readTree(doc.get(SUMMARY_JSON));
        List<StoredSummary.Statement> statements = new ArrayList<>();
        for (JsonNode row : json.path("statements")) {
            List<String> pages = new ArrayList<>();
            row.path("pageIds").forEach(page -> pages.add(page.asString()));
            statements.add(new StoredSummary.Statement(row.path("text").asString(), pages));
        }
        List<StoredSummary.InputPage> inputs = new ArrayList<>();
        for (JsonNode page : json.path("inputPages")) {
            inputs.add(new StoredSummary.InputPage(page.path("spaceKey").asString(), page.path("pageId").asString(),
                    page.path("content").asBoolean(false)));
        }
        return new StoredSummary(doc.get(SUMMARY_SOURCE_ID), doc.get(SUMMARY_UPDATE), json.path("text").asString(),
                statements, json.path("model").asString(), Instant.parse(json.path("createdAt").asString()),
                Instant.parse(json.path("at").asString()), json.path("unreadableChanges").asInt(),
                json.path("reportSha256").asString(), inputs);
    }

    private static void stored(Document doc, String field, String value) {
        if (value != null) {
            doc.add(new StoredField(field, value));
        }
    }
}
