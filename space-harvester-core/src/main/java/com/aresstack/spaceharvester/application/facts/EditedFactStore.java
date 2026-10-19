package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Die Fakten mit den manuellen und geprüften aus der {@link FactEditStore Prüfablage} (#182), wie der Faktenspeicher
 * des Enterprise AI Client sie zeigt:
 *
 * <ul>
 *   <li>Manuelle Fakten erscheinen bei ihrer Seite; kein Prozessorlauf berührt sie.</li>
 *   <li>Ein geprüfter automatischer Fakt trägt seinen Prüfstatus, solange das Ergebnis dieselbe Aussage liefert
 *       ({@link MetadataFact#sameStatement}).</li>
 *   <li>Liefert das neu berechnete Ergebnis an dieser Stelle etwas anderes oder nichts mehr, bleibt die geprüfte
 *       Kopie mit alter Herkunft und Kennung stehen (dann veraltet); der neue automatische Fakt erscheint ungeprüft
 *       unter einer abgeleiteten Kennung, wie ein neuer Fakt im Client eine neue Kennung bekommt.</li>
 * </ul>
 *
 * Die Ablage der Anreicherungen bleibt unberührt.
 */
public final class EditedFactStore implements FactStore {

    private static final int ID_BYTES = 16;

    private final FactStore base;
    private final FactEditStore edits;

    public EditedFactStore(FactStore base, FactEditStore edits) {
        this.base = Objects.requireNonNull(base, "base");
        this.edits = Objects.requireNonNull(edits, "edits");
    }

    /** Die Fakten der Ablage der Anreicherungen, ohne Prüfablage. */
    public FactStore base() {
        return base;
    }

    @Override
    public List<MetadataFact> factsOf(PageKey page) {
        return resolve(page, base.factsOf(page), edits.forPage(page)).facts();
    }

    @Override
    public List<MetadataFact> facts(Query query, String after, int limit) {
        Objects.requireNonNull(query, "query");
        Map<String, MetadataFact> replaced = new HashMap<>();
        Set<String> excluded = new HashSet<>();
        List<MetadataFact> extras = new ArrayList<>();
        Map<PageKey, List<FactEdit>> byPage = new LinkedHashMap<>();
        for (FactEdit edit : edits.all()) {
            if (query.sourceIds().contains(edit.fact().page().sourceId())) {
                byPage.computeIfAbsent(edit.fact().page(), key -> new ArrayList<>()).add(edit);
            }
        }
        byPage.forEach((page, pageEdits) -> {
            Resolution resolution = resolve(page, base.factsOf(page), pageEdits);
            replaced.putAll(resolution.replaced());
            excluded.addAll(resolution.excluded());
            extras.addAll(resolution.extras());
        });
        if (byPage.isEmpty()) {
            return base.facts(query, after, limit);
        }
        List<MetadataFact> candidates = new ArrayList<>();
        String cursor = after;
        boolean exhausted = false;
        while (candidates.size() < limit && !exhausted) {
            List<MetadataFact> next = base.facts(query, cursor, limit);
            for (MetadataFact fact : next) {
                cursor = fact.id();
                if (!excluded.contains(fact.id())) {
                    candidates.add(replaced.getOrDefault(fact.id(), fact));
                }
            }
            exhausted = next.size() < limit;
        }
        String last = cursor;
        boolean complete = exhausted;
        for (MetadataFact extra : extras) {
            if (query.matches(extra) && (after == null || extra.id().compareTo(after) > 0)
                    && (complete || last == null || extra.id().compareTo(last) <= 0)) {
                candidates.add(extra);
            }
        }
        candidates.sort(Comparator.comparing(MetadataFact::id));
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }

    /** Wendet die Einträge einer Seite auf ihre automatischen Fakten an. */
    static Resolution resolve(PageKey page, List<MetadataFact> automatic, List<FactEdit> pageEdits) {
        Map<String, FactEdit> open = new LinkedHashMap<>();
        pageEdits.stream().sorted(Comparator.comparing(FactEdit::id)).forEach(edit -> open.put(edit.id(), edit));
        List<MetadataFact> facts = new ArrayList<>();
        Map<String, MetadataFact> replaced = new HashMap<>();
        Set<String> excluded = new LinkedHashSet<>();
        List<MetadataFact> extras = new ArrayList<>();
        for (MetadataFact fact : automatic) {
            MetadataFact effective = fact;
            FactEdit edit = open.get(fact.id());
            if (edit != null && !matches(edit, fact)) {
                // Die geprüfte Kopie behält die Kennung; der neue Fakt bekommt eine eigene.
                effective = fact.withId(successorId(fact));
                excluded.add(fact.id());
                edit = open.get(effective.id());
            }
            if (edit != null && matches(edit, effective)) {
                open.remove(effective.id());
                effective = effective.withReview(edit.fact().review());
            }
            facts.add(effective);
            if (excluded.contains(fact.id())) {
                extras.add(effective);
            } else if (effective != fact) {
                replaced.put(fact.id(), effective);
            }
        }
        for (FactEdit edit : open.values()) {
            facts.add(edit.fact());
            extras.add(edit.fact());
        }
        return new Resolution(facts, replaced, excluded, extras);
    }

    private static boolean matches(FactEdit edit, MetadataFact fact) {
        return edit.fact().origin() == FactOrigin.AUTOMATIC && edit.fact().sameStatement(fact);
    }

    /** Kennung eines neuen automatischen Fakts an der Stelle eines geprüften, der nicht mehr passt. */
    static String successorId(MetadataFact fact) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((fact.id() + '\u0000'
                    + fact.provenance().processorVersion() + '\u0000' + fact.provenance().inputSha256() + '\u0000'
                    + fact.payloadJson()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, ID_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param facts    das Faktenblatt der Seite
     * @param replaced automatische Fakten mit Prüfstatus, nach Kennung
     * @param excluded Kennungen automatischer Fakten, die unter neuer Kennung erscheinen
     * @param extras   alles, was nicht unter einer Kennung der Ablage der Anreicherungen erscheint
     */
    record Resolution(List<MetadataFact> facts, Map<String, MetadataFact> replaced, Set<String> excluded,
                      List<MetadataFact> extras) {
    }
}
