package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Suchmethode „Nur Fachbegriffe“ (K5b): durchsucht nur das Fachglossar, vorgeschlagene und bestätigte Einträge, nie
 * abgelehnte, und wie der Reiter „Fachglossar“ ohne die Einordnung {@code TECHNISCH}, in derselben Reihenfolge
 * ({@code GlossaryOverview.ROW_ORDER}: Wichtige zuerst, ohne Wichtigkeit hinten, sonst nach Suchwert). Ein Eintrag
 * passt, wenn der Suchtext ein Präfix des Begriffs oder eines seiner Wörter ist (wie die Glossarliste) oder eines
 * seiner Synonyme ({@link RelationType#SYNONYM}, vorgeschlagen oder bestätigt, deren Bereich den Eintrag abdeckt).
 * Rechte je Eintrag und je Beziehung wie {@link Glossary#entries} und {@link TermRelations#relations}; nichts
 * gespeichert, keine neuen Zustände. Der Cursor ist der Schlüssel der letzten Zeile (versioniert), nicht ihre
 * Position: Ein Ablehnen dazwischen überspringt und doppelt nichts.
 */
public final class TechnicalTerms {

    /** Höchstens so viele Treffer je Abruf. */
    public static final int MAX_LIMIT = Glossary.MAX_LIMIT;
    private static final String CURSOR_VERSION = "t1";

    private final BiFunction<AccessContext, Scope, List<GlossaryEntry>> openEntries;
    private final BiFunction<AccessContext, Scope, List<TermRelation>> synonyms;
    private final BiFunction<AccessContext, Scope, Map<String, GlossaryDerivation.Importance>> importance;
    private final TermRelations relations;
    /** Die zuletzt berechnete Wichtigkeit (je Aufrufer und Bereich), bis {@link #forget()}. */
    private volatile Memo memo;

    private record Memo(AccessContext caller, Scope scope, Map<String, GlossaryDerivation.Importance> importance) {
    }

    /** Ohne Wichtigkeit: alle Einträge nach Suchwert (wie im Reiter die Einträge ohne Wichtigkeit). */
    public TechnicalTerms(Glossary glossary, TermRelations relations) {
        this(glossary, relations, (caller, scope) -> Map.of());
    }

    /**
     * @param importance Wichtigkeit je Suchwert im Bereich ({@code GlossaryOverview.importance}); gehalten, bis
     *                   {@link #forget()} gerufen wird
     */
    public TechnicalTerms(Glossary glossary, TermRelations relations,
                          BiFunction<AccessContext, Scope, Map<String, GlossaryDerivation.Importance>> importance) {
        this((caller, scope) -> open(glossary, caller, scope), (caller, scope) -> synonyms(relations, caller, scope),
                importance, Objects.requireNonNull(relations, "relations"));
        Objects.requireNonNull(glossary, "glossary");
    }

    /**
     * @param openEntries die sichtbaren, nicht abgelehnten, nicht technischen Glossareinträge des Aufrufers
     * @param synonyms    die sichtbaren, nicht abgelehnten Synonymbeziehungen des Aufrufers
     */
    public TechnicalTerms(BiFunction<AccessContext, Scope, List<GlossaryEntry>> openEntries,
                          BiFunction<AccessContext, Scope, List<TermRelation>> synonyms) {
        this(openEntries, synonyms, (caller, scope) -> Map.of(), null);
    }

    /** Wie oben, mit Wichtigkeit je Suchwert. */
    public TechnicalTerms(BiFunction<AccessContext, Scope, List<GlossaryEntry>> openEntries,
                          BiFunction<AccessContext, Scope, List<TermRelation>> synonyms,
                          BiFunction<AccessContext, Scope, Map<String, GlossaryDerivation.Importance>> importance) {
        this(openEntries, synonyms, importance, null);
    }

    private TechnicalTerms(BiFunction<AccessContext, Scope, List<GlossaryEntry>> openEntries,
                           BiFunction<AccessContext, Scope, List<TermRelation>> synonyms,
                           BiFunction<AccessContext, Scope, Map<String, GlossaryDerivation.Importance>> importance,
                           TermRelations relations) {
        this.openEntries = Objects.requireNonNull(openEntries, "openEntries");
        this.synonyms = Objects.requireNonNull(synonyms, "synonyms");
        this.importance = Objects.requireNonNull(importance, "importance");
        this.relations = relations;
    }

    /**
     * Bereich der Suche wie bei der übrigen Suche: ohne Quellen alle, sonst diese Quellen (globale Einträge gelten
     * immer), mit Spaces in ihnen nur, was für die Quelle oder einen dieser Spaces gilt.
     *
     * @param sourceIds Quellen; leer heißt alle
     * @param spaceKeys Spaces; leer heißt alle
     */
    public record Scope(Set<String> sourceIds, Set<String> spaceKeys) {

        public static final Scope ALL = new Scope(Set.of(), Set.of());

        public Scope {
            sourceIds = sourceIds == null ? Set.of() : Set.copyOf(sourceIds);
            spaceKeys = spaceKeys == null || sourceIds.isEmpty() ? Set.of() : Set.copyOf(spaceKeys);
        }

        /** Eine Quelle (oder alle mit {@code null}), darin ein Space oder alle. */
        public Scope(String sourceId, String spaceKey) {
            this(sourceId == null ? Set.of() : Set.of(sourceId),
                    sourceId == null || spaceKey == null ? Set.of() : Set.of(spaceKey));
        }

        /** Ob ein Eintrag oder eine Beziehung dieses Geltungsbereichs in die Suche gehört. */
        public boolean includes(RelationScope scope) {
            return scope.global() || sourceIds.isEmpty() || sourceIds.contains(scope.sourceId())
                    && (spaceKeys.isEmpty() || scope.spaceKey() == null || spaceKeys.contains(scope.spaceKey()));
        }

        /** Die eine Quelle, wenn es genau eine ist, sonst {@code null}. */
        String singleSource() {
            return sourceIds.size() == 1 ? sourceIds.iterator().next() : null;
        }
    }

    /**
     * Ein Treffer.
     *
     * @param entry      der Fachbegriff
     * @param viaSynonym das Synonym, über das er gefunden wurde, oder {@code null}, wenn der Begriff selbst passt
     *                   (oder ohne Suchtext)
     */
    public record Hit(GlossaryEntry entry, String viaSynonym) {

        public Hit {
            Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * Ein Stapel Treffer in der Reihenfolge des Reiters „Fachglossar“.
     *
     * @param nextCursor zum Weiterlesen oder {@code null} am Ende
     * @param total      Treffer insgesamt
     */
    public record Page(List<Hit> hits, String nextCursor, int total) {

        public Page {
            hits = List.copyOf(hits);
        }
    }

    /** Vergisst die gehaltene Wichtigkeit, etwa nach neuen Aussagenanalysen. */
    public void forget() {
        memo = null;
    }

    /**
     * Fachbegriffe zum Suchtext.
     *
     * @param q      Suchtext; leer oder {@code null} heißt alle
     * @param scope  Bereich, {@code null} wie {@link Scope#ALL}
     * @param limit  1 bis {@link #MAX_LIMIT}
     * @param cursor {@code nextCursor} der vorigen Seite oder {@code null}
     * @throws InvalidQueryException bei limit außerhalb oder beschädigtem cursor
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException bei unbekannter Quelle
     */
    public Page search(AccessContext caller, String q, Scope scope, int limit, String cursor) {
        Scope within = scope == null ? Scope.ALL : scope;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        Key after = cursor == null || cursor.isBlank() ? null : Key.decode(cursor);
        String query = q == null || q.isBlank() ? null : TermText.normalize(q);
        List<GlossaryEntry> entries = openEntries.apply(caller, within).stream()
                .filter(entry -> within.includes(entry.scope())).toList();
        List<TermRelation> links = query == null ? List.of() : synonyms.apply(caller, within).stream()
                .filter(relation -> within.includes(relation.scope())).toList();
        Map<String, GlossaryDerivation.Importance> weights = importance(caller, within);
        List<Hit> found = new ArrayList<>();
        for (GlossaryEntry entry : entries) {
            if (query == null || Glossary.matches(entry.normalized(), query)) {
                found.add(new Hit(entry, null));
            } else {
                String via = viaSynonym(entry, links, query);
                if (via != null) {
                    found.add(new Hit(entry, via));
                }
            }
        }
        found.sort(Comparator.comparing(hit -> Key.of(hit.entry(), weights), Key.ORDER));
        List<Hit> page = new ArrayList<>();
        String next = null;
        for (Hit hit : found) {
            Key key = Key.of(hit.entry(), weights);
            if (after != null && Key.ORDER.compare(key, after) <= 0) {
                continue;
            }
            if (page.size() == limit) {
                next = Key.of(page.get(page.size() - 1).entry(), weights).encode();
                break;
            }
            page.add(hit);
        }
        return new Page(page, next, found.size());
    }

    /**
     * Die nicht abgelehnten Synonyme eines Fachbegriffs, deren Bereich den Begriff abdeckt (global, seine Quelle, sein
     * Space), sortiert nach dem anderen Ende.
     */
    public List<TermRelation> synonymsOf(AccessContext caller, GlossaryEntry term) {
        Objects.requireNonNull(term, "term");
        RelationScope own = term.scope();
        List<TermRelation> found = new ArrayList<>(synonyms.apply(caller, new Scope(own.sourceId(), own.spaceKey()))
                .stream().filter(relation -> relation.touches(term.normalized()) && covers(relation.scope(), own))
                .toList());
        found.sort(Comparator.comparing(relation -> TermText.normalize(relation.other(term.normalized())),
                TermIndex.ORDER));
        return found;
    }

    /**
     * Fügt ein Synonym hinzu (Angelo 21.11.2026: global). Gibt es dasselbe Synonym schon in einem Bereich, der den
     * Begriff abdeckt, wird es bestätigt statt doppelt angelegt; ein abgelehntes globales kommt bestätigt zurück.
     *
     * @throws InvalidQueryException wenn das Synonym leer ist oder dem Begriff gleicht
     */
    public TermRelation addSynonym(AccessContext caller, GlossaryEntry term, String synonym) {
        Objects.requireNonNull(term, "term");
        if (relations == null) {
            throw new IllegalStateException("ohne Beziehungen");
        }
        String text = synonym == null ? "" : synonym.strip();
        String normalized = TermText.normalize(text);
        if (normalized.isEmpty() || normalized.equals(term.normalized())) {
            throw new InvalidQueryException("Das Synonym muss sich vom Begriff unterscheiden");
        }
        for (TermRelation relation : relations.forTerms(caller, List.of(term.normalized()),
                term.scope().sourceId(), term.scope().spaceKey())) {
            if (relation.type() == RelationType.SYNONYM && relation.touches(normalized)
                    && covers(relation.scope(), term.scope())) {
                return relation.status() == ReviewStatus.CONFIRMED ? relation
                        : relations.confirm(caller, relation.id(), relation.revision());
            }
        }
        return relations.add(caller, new TermRelations.NewRelation(term.term(), text, RelationType.SYNONYM,
                RelationScope.GLOBAL, null, List.of()));
    }

    /** Ob eine Beziehung des Bereichs {@code relation} für einen Eintrag des Bereichs {@code entry} gilt. */
    static boolean covers(RelationScope relation, RelationScope entry) {
        return relation.global() || relation.sourceId().equals(entry.sourceId())
                && (relation.spaceKey() == null || relation.spaceKey().equals(entry.spaceKey()));
    }

    /** Das erste passende Synonym des Eintrags (nach Suchwert), dessen Bereich ihn abdeckt, oder {@code null}. */
    private static String viaSynonym(GlossaryEntry entry, List<TermRelation> links, String query) {
        String best = null;
        for (TermRelation relation : links) {
            if (!relation.touches(entry.normalized()) || !covers(relation.scope(), entry.scope())) {
                continue;
            }
            String other = relation.other(entry.normalized());
            if (Glossary.matches(TermText.normalize(other), query) && (best == null
                    || TermIndex.ORDER.compare(TermText.normalize(other), TermText.normalize(best)) < 0)) {
                best = other;
            }
        }
        return best;
    }

    private Map<String, GlossaryDerivation.Importance> importance(AccessContext caller, Scope scope) {
        Memo held = memo;
        if (held != null && Objects.equals(held.caller(), caller) && held.scope().equals(scope)) {
            return held.importance();
        }
        Map<String, GlossaryDerivation.Importance> computed = importance.apply(caller, scope);
        Map<String, GlossaryDerivation.Importance> result = computed == null ? Map.of() : Map.copyOf(computed);
        memo = new Memo(caller, scope, result);
        return result;
    }

    /** Sortierschlüssel wie {@code GlossaryOverview.ROW_ORDER}; zugleich der Cursor. */
    private record Key(boolean none, int pages, int asActor, int mentions, String normalized) {

        static final Comparator<Key> ORDER = Comparator.comparing(Key::none)
                .thenComparing(Comparator.comparingInt(Key::pages).reversed())
                .thenComparing(Comparator.comparingInt(Key::asActor).reversed())
                .thenComparing(Comparator.comparingInt(Key::mentions).reversed())
                .thenComparing(Key::normalized, TermIndex.ORDER);

        static Key of(GlossaryEntry entry, Map<String, GlossaryDerivation.Importance> weights) {
            GlossaryDerivation.Importance weight = weights.get(entry.normalized());
            return weight == null ? new Key(true, 0, 0, 0, entry.normalized())
                    : new Key(false, weight.pages(), weight.asActor(), weight.mentions(), entry.normalized());
        }

        String encode() {
            String value = String.join("\u0000", CURSOR_VERSION, none ? "1" : "0", Integer.toString(pages),
                    Integer.toString(asActor), Integer.toString(mentions), normalized);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        static Key decode(String cursor) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split("\u0000", -1);
                if (parts.length != 6 || !CURSOR_VERSION.equals(parts[0])) {
                    throw new InvalidQueryException("cursor ist beschädigt");
                }
                return new Key("1".equals(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]), parts[5]);
            } catch (IllegalArgumentException e) {
                throw new InvalidQueryException("cursor ist beschädigt");
            }
        }
    }

    private static List<GlossaryEntry> open(Glossary glossary, AccessContext caller, Scope scope) {
        List<GlossaryEntry> all = new ArrayList<>();
        String cursor = null;
        String sourceId = scope.singleSource();
        String spaceKey = sourceId != null && scope.spaceKeys().size() == 1 ? scope.spaceKeys().iterator().next()
                : null;
        do {
            Glossary.Page page = glossary.entries(caller, new Glossary.Search(null, null, null, sourceId, spaceKey,
                    null, Glossary.MAX_LIMIT, cursor, GlossaryOverview.Query.WITHOUT_TECHNICAL));
            all.addAll(page.entries());
            cursor = page.nextCursor();
        } while (cursor != null);
        return all;
    }

    private static List<TermRelation> synonyms(TermRelations relations, AccessContext caller, Scope scope) {
        List<TermRelation> all = new ArrayList<>();
        String cursor = null;
        String sourceId = scope.singleSource();
        do {
            TermRelations.RelationList list = relations.relations(caller, new TermRelations.RelationSearch(null,
                    RelationType.SYNONYM, null, null, sourceId, TermRelations.MAX_LIMIT, cursor));
            all.addAll(list.relations());
            cursor = list.nextCursor();
        } while (cursor != null);
        return all;
    }
}
