package com.aresstack.spaceharvester.app.ui.controls;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Die zuletzt gesuchten Begriffe, der neueste zuerst, ohne Doppelte (Angelo, 28.11.2026). Wie der Verlauf am
 * Zurück-Knopf höchstens {@link BackButton#MAX_ENTRIES} Einträge; jede Änderung geht sofort an {@code save}, damit
 * der Verlauf einen Neustart übersteht.
 */
public final class RecentTerms {

    /** So viele Begriffe merkt sich der Verlauf höchstens, wie der Verlauf am Zurück-Knopf. */
    public static final int MAX_TERMS = BackButton.MAX_ENTRIES;

    private final List<String> terms = new ArrayList<>();
    private final Consumer<List<String>> save;

    /**
     * @param stored die gespeicherten Begriffe, der neueste zuerst
     * @param save   nimmt nach jeder Änderung die ganze Liste entgegen
     */
    public RecentTerms(List<String> stored, Consumer<List<String>> save) {
        this.save = Objects.requireNonNull(save, "save");
        for (String term : stored == null ? List.<String>of() : stored) {
            String clean = clean(term);
            if (!clean.isEmpty() && !terms.contains(clean) && terms.size() < MAX_TERMS) {
                terms.add(clean);
            }
        }
    }

    /** Die Begriffe, der neueste zuerst. */
    public List<String> terms() {
        return List.copyOf(terms);
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /** Merkt einen gesuchten Begriff ganz oben; ein schon bekannter rückt nach oben, der älteste fällt bei Bedarf weg. */
    public void add(String term) {
        String clean = clean(term);
        if (clean.isEmpty() || (!terms.isEmpty() && terms.get(0).equals(clean))) {
            return;
        }
        terms.remove(clean);
        terms.add(0, clean);
        while (terms.size() > MAX_TERMS) {
            terms.remove(terms.size() - 1);
        }
        save.accept(terms());
    }

    /** Entfernt einen Begriff (Shift+Entf in der Liste, wie in Windows-Dropdowns). */
    public void remove(String term) {
        if (terms.remove(clean(term))) {
            save.accept(terms());
        }
    }

    private static String clean(String term) {
        return term == null ? "" : term.strip().replaceAll("\\s+", " ");
    }
}
