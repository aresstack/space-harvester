package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Suchmethode „Nur Fachbegriffe“ (K5b): durchsucht nur das Fachglossar, vorgeschlagene und bestätigte Einträge, nie
 * abgelehnte, und wie der Reiter „Fachglossar“ ohne die Einordnung {@code TECHNISCH}, in derselben Reihenfolge. Ein Eintrag passt, wenn der Suchtext ein Präfix des Begriffs oder eines seiner Wörter ist (wie die
 * Glossarliste) oder eines seiner Synonyme ({@link RelationType#SYNONYM}, vorgeschlagen oder bestätigt). Rechte je
 * Eintrag und je Beziehung wie {@link Glossary#entries} und {@link TermRelations#relations}; nichts gespeichert, keine
 * neuen Zustände. Die Synonyme eines Eintrags liefert {@link TermRelations#relations} mit {@code term} und
 * {@code type=SYNONYM}.
 */
public final class TechnicalTerms {

    /** Höchstens so viele Treffer je Abruf. */
    public static final int MAX_LIMIT = Glossary.MAX_LIMIT;

    private final BiFunction<AccessContext, Scope, List<GlossaryEntry>> openEntries;
    private final BiFunction<AccessContext, Scope, List<TermRelation>> synonyms;

    public TechnicalTerms(Glossary glossary, TermRelations relations) {
        this((caller, scope) -> open(glossary, caller, scope), (caller, scope) -> synonyms(relations, caller, scope));
        Objects.requireNonNull(glossary, "glossary");
        Objects.requireNonNull(relations, "relations");
    }

    /**
     * @param openEntries die sichtbaren, nicht abgelehnten, nicht technischen Glossareinträge des Aufrufers im Bereich
     * @param synonyms    die sichtbaren, nicht abgelehnten Synonymbeziehungen des Aufrufers im Bereich
     */
    public TechnicalTerms(BiFunction<AccessContext, Scope, List<GlossaryEntry>> openEntries,
                          BiFunction<AccessContext, Scope, List<TermRelation>> synonyms) {
        this.openEntries = Objects.requireNonNull(openEntries, "openEntries");
        this.synonyms = Objects.requireNonNull(synonyms, "synonyms");
    }

    /**
     * Bereich der Suche wie bei {@link Glossary.Search}: ohne Quelle alle, mit Quelle globale und für sie geltende
     * Einträge, mit Space dazu nur, was für den Space gilt.
     */
    public record Scope(String sourceId, String spaceKey) {

        public static final Scope ALL = new Scope(null, null);
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
     * Ein Stapel Treffer, sortiert nach Suchwert.
     *
     * @param nextCursor zum Weiterlesen oder {@code null} am Ende
     * @param total      Treffer insgesamt
     */
    public record Page(List<Hit> hits, String nextCursor, int total) {

        public Page {
            hits = List.copyOf(hits);
        }
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
        int offset = offset(cursor);
        String query = q == null || q.isBlank() ? null : TermText.normalize(q);
        Map<String, String> viaSynonym = query == null ? Map.of() : bySynonym(caller, within, query);
        List<GlossaryEntry> entries = new ArrayList<>(openEntries.apply(caller, within));
        entries.sort(Comparator.comparing(Glossary::sortKey, TermIndex.ORDER));
        List<Hit> found = new ArrayList<>();
        for (GlossaryEntry entry : entries) {
            if (query == null || Glossary.matches(entry.normalized(), query)) {
                found.add(new Hit(entry, null));
            } else if (viaSynonym.containsKey(entry.normalized())) {
                found.add(new Hit(entry, viaSynonym.get(entry.normalized())));
            }
        }
        int from = Math.min(offset, found.size());
        int to = Math.min(from + limit, found.size());
        return new Page(found.subList(from, to), to < found.size() ? Integer.toString(to) : null, found.size());
    }

    /** Suchwert eines Begriffs → das erste passende Synonym (nach Suchwert). */
    private Map<String, String> bySynonym(AccessContext caller, Scope scope, String query) {
        Map<String, String> result = new HashMap<>();
        for (TermRelation relation : synonyms.apply(caller, scope)) {
            String from = TermText.normalize(relation.fromTerm());
            String to = TermText.normalize(relation.toTerm());
            if (Glossary.matches(to, query)) {
                result.merge(from, relation.toTerm(), TechnicalTerms::first);
            }
            if (Glossary.matches(from, query)) {
                result.merge(to, relation.fromTerm(), TechnicalTerms::first);
            }
        }
        return result;
    }

    private static String first(String a, String b) {
        return TermIndex.ORDER.compare(TermText.normalize(a), TermText.normalize(b)) <= 0 ? a : b;
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(cursor);
            if (value < 0) {
                throw new NumberFormatException(cursor);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new InvalidQueryException("cursor ist beschädigt");
        }
    }

    private static List<GlossaryEntry> open(Glossary glossary, AccessContext caller, Scope scope) {
        List<GlossaryEntry> all = new ArrayList<>();
        String cursor = null;
        do {
            Glossary.Page page = glossary.entries(caller, new Glossary.Search(null, null, null, scope.sourceId(),
                    scope.sourceId() == null ? null : scope.spaceKey(), null, Glossary.MAX_LIMIT, cursor,
                    GlossaryOverview.Query.WITHOUT_TECHNICAL));
            all.addAll(page.entries());
            cursor = page.nextCursor();
        } while (cursor != null);
        return all;
    }

    private static List<TermRelation> synonyms(TermRelations relations, AccessContext caller, Scope scope) {
        List<TermRelation> all = new ArrayList<>();
        String cursor = null;
        do {
            TermRelations.RelationList list = relations.relations(caller, new TermRelations.RelationSearch(null,
                    RelationType.SYNONYM, null, null, scope.sourceId(), TermRelations.MAX_LIMIT, cursor));
            all.addAll(list.relations());
            cursor = list.nextCursor();
        } while (cursor != null);
        return all;
    }
}
