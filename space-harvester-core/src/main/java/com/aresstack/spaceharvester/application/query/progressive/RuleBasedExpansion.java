package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.query.SearchTerms;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Varianten ohne Modell (ADR 0017): erst alle Suchbegriffe der Frage gemeinsam, dann wird je Schritt der schwächste
 * Begriff weggelassen (gewöhnliche Wörter vor Namen vor Kennungen, bei Gleichstand der spätere), bis zwei bleiben;
 * zuletzt die stärksten Begriffe einzeln. So wächst die Liste langsam und bleibt nah an der Absicht des Nutzers. Linear
 * in der Zahl der Begriffe, nie alle Kombinationen.
 */
public final class RuleBasedExpansion implements QueryExpansionStrategy {

    /** Höchstens so viele Begriffe gehen in die erste Variante. */
    static final int MAX_TERMS = 8;

    @Override
    public List<String> expand(String original, int maxVariants) {
        List<String> terms = SearchTerms.terms(original);
        if (terms.size() > MAX_TERMS) {
            terms = weakestLast(terms).subList(0, MAX_TERMS);
            List<String> kept = terms;
            terms = SearchTerms.terms(original).stream().filter(kept::contains).toList();
        }
        Set<String> variants = new LinkedHashSet<>();
        List<String> current = new ArrayList<>(terms);
        while (current.size() >= 2) {
            variants.add(String.join(" ", current));
            current.remove(weakest(current));
        }
        for (String term : weakestLast(terms)) {
            variants.add(term);
        }
        String normalized = normalize(original);
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(normalized);
        for (String variant : variants) {
            if (result.size() >= maxVariants) {
                break;
            }
            if (seen.add(normalize(variant))) {
                result.add(variant);
            }
        }
        return List.copyOf(result);
    }

    /** Der schwächste Begriff: geringste Stärke, bei Gleichstand der spätere. */
    private static String weakest(List<String> terms) {
        String weakest = terms.getFirst();
        for (String term : terms) {
            if (SearchTerms.strength(term) <= SearchTerms.strength(weakest)) {
                weakest = term;
            }
        }
        return weakest;
    }

    /** Die Begriffe vom stärksten zum schwächsten, bei Gleichstand in der Reihenfolge der Frage. */
    private static List<String> weakestLast(List<String> terms) {
        List<String> sorted = new ArrayList<>(terms);
        sorted.sort(Comparator.comparingInt((String term) -> -SearchTerms.strength(term))
                .thenComparingInt(terms::indexOf));
        return sorted;
    }

    static String normalize(String text) {
        return String.join(" ", text.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    }
}
