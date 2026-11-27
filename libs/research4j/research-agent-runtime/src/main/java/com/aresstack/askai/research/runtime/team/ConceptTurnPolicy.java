package com.aresstack.askai.research.runtime.team;

import java.util.Locale;

/**
 * The MACHINE-side intent separation of one user turn (safety-slice gate 2 finding: with rewrite
 * withdrawn, the model simply substituted partial adds for a compound restructuring wish — and a
 * concept DELETE wish must never silently become a scope exclusion; those are two different user
 * intents). Deliberately a dumb keyword classifier: deterministic, test-pinned, biased toward
 * safety — a restructure false positive only defers additions with an honest sentence, it never
 * destroys; the stricter DELETE mode (whose answer the host replaces entirely) therefore
 * demands verb AND structure noun.
 */
public final class ConceptTurnPolicy {

    /** What the model may do to the concept within THIS turn. */
    public enum Mode {
        /** Normal turn: read/add/rename/exclude/resolve/offer. */
        FULL,
        /**
         * A compound restructuring wish ("überarbeite/ersetze/strukturiere den Zweig"): concept
         * MUTATIONS are refused for the whole turn (no partial-add substitutes) until the
         * plan-validate-stage-commit workflow exists; reading, offering and the exclusion
         * facade stay available.
         */
        RESTRUCTURE_READ_ONLY,
        /**
         * An explicit concept DELETE wish ("lösche die Karte/den Zweig"): no concept mutation,
         * no exclude, and a TERMINAL host-deterministic answer (the model once claimed the
         * branch was deleted over an unchanged concept). Because the whole visible answer is
         * replaced, this mode demands verb AND structure noun — a bare "lösche X" stays
         * conversational, exactly like a bare "entferne X".
         */
        DELETE_READ_ONLY,
        /**
         * An explicit MOVE order (move_leaf slice): FULL permissions, but the receipt-truth
         * guard arms — a turn without a MOVED receipt closes with the deterministic host
         * sentence instead of the model's narration ("verschoben" only when a receipt of the
         * CURRENT turn covers it; the add_cards gate saw a NONE turn claim an executed change).
         */
        MOVE_TRUTH,
        /**
         * An explicit EXCLUSION order ("Schließe X aus der Recherche aus"): FULL permissions,
         * but the receipt-truth guard arms. The connector gate saw the model detour into a
         * scopePatch operation kind 'exclude' (REJECTED) and then claim the card was excluded
         * over an unchanged scope — a turn without a terminal EXCLUDED receipt closes with the
         * deterministic host sentence instead. A committed exclusion never reaches that close:
         * the EXCLUDE action is terminal with the host's receipt answer.
         */
        EXCLUDE_TRUTH
    }

    private static final String[] DELETE_VERBS = {
            "lösch", "loesch", "delete", "streich", "entfern", "remove"
    };
    private static final String[] STRUCTURE_NOUNS = {
            "zweig", "karte", "knoten", "ast", "branch", "card", "node", "konzept", "concept"
    };
    private static final String[] RESTRUCTURE_VERBS = {
            "überarbeit", "ueberarbeit", "ersetz", "umstrukturier", "restrukturier",
            "strukturiere", "gliedere", "reorganis", "reorganiz", "rewrite", "restructur",
            "umbau"
    };
    // "verschieb" left the restructure blockers with the real move_leaf tool; its detection
    // lives in hasUnnegatedMoveMention (word-bounded, negation-aware).

    private ConceptTurnPolicy() {
    }

    /** Classify one user prompt. Delete wins over restructure (it is the stricter mode). */
    public static Mode modeFor(String userPrompt) {
        String prompt = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
        if (containsAny(prompt, DELETE_VERBS) && containsAny(prompt, STRUCTURE_NOUNS)) {
            return Mode.DELETE_READ_ONLY;
        }
        if (containsAny(prompt, RESTRUCTURE_VERBS)) {
            return Mode.RESTRUCTURE_READ_ONLY;
        }
        // "move" as a WORD only — "remove"/"movement" must not arm the truth guard; and a
        // NEGATED mention never arms it ("kein Verschieben" once flipped a successful add
        // turn into a false host 'nothing changed' answer).
        if (hasUnnegatedMoveMention(prompt)) {
            return Mode.MOVE_TRUTH;
        }
        if (hasUnnegatedExcludeMention(prompt)) {
            return Mode.EXCLUDE_TRUTH;
        }
        return Mode.FULL;
    }

    /** Negation tokens that, right before a move verb, mark it as NOT an order. */
    private static final String[] NEGATIONS = {
            "kein", "nicht", "ohne", "statt", "anstatt", "not ", "no ", "never", "without",
            "don't", "dont"
    };

    private static boolean hasUnnegatedMoveMention(String prompt) {
        java.util.List<Integer> hits = new java.util.ArrayList<Integer>();
        int from = 0;
        while (true) {
            int index = prompt.indexOf("verschieb", from);
            if (index < 0) {
                break;
            }
            hits.add(index);
            from = index + 1;
        }
        java.util.regex.Matcher move = java.util.regex.Pattern
                .compile("(?<![a-zäöüß])move(?![a-zäöüß])").matcher(prompt);
        while (move.find()) {
            hits.add(move.start());
        }
        for (int index : hits) {
            String window = prompt.substring(Math.max(0, index - 28), index);
            // A negation only counts within the SAME clause — "Nicht löschen! Verschiebe …"
            // is an order, "… kein Verschieben." is not.
            for (char delimiter : new char[] {'.', '!', '?', ';'}) {
                int cut = window.lastIndexOf(delimiter);
                if (cut >= 0) {
                    window = window.substring(cut + 1);
                }
            }
            boolean negated = false;
            for (String negation : NEGATIONS) {
                if (window.contains(negation)) {
                    negated = true;
                    break;
                }
            }
            if (!negated) {
                return true; // ONE unnegated mention is an order
            }
        }
        return false;
    }

    /**
     * Exclusion-ORDER detection: verb forms only ("ausschließen"/"exclude" and the separable
     * "schließe … aus"), never "ausschließlich", never past-tense reports ("ausgeschlossen"),
     * never questions (a clause ending in '?'), never negated mentions — a false positive
     * would replace a legitimate answer with the deterministic sentence.
     */
    private static boolean hasUnnegatedExcludeMention(String prompt) {
        java.util.List<Integer> hits = new java.util.ArrayList<Integer>();
        java.util.regex.Matcher compound = java.util.regex.Pattern
                .compile("ausschlie(?:ß|ss)(?!lich)").matcher(prompt);
        while (compound.find()) {
            hits.add(compound.start());
        }
        java.util.regex.Matcher english = java.util.regex.Pattern
                .compile("(?<![a-z])exclude").matcher(prompt);
        while (english.find()) {
            hits.add(english.start());
        }
        java.util.regex.Matcher separable = java.util.regex.Pattern
                .compile("schlie(?:ß|ss)").matcher(prompt);
        while (separable.find()) {
            int index = separable.start();
            if (index >= 3 && prompt.startsWith("aus", index - 3)) {
                continue; // part of the compound form, decided above (incl. "ausschließlich")
            }
            // The separable verb is an exclusion only with a word-bounded "aus" later in the
            // SAME clause — "Schließe die Sitzung." stays conversational.
            if (java.util.regex.Pattern.compile("(?<![a-zäöüß])aus(?![a-zäöüß])")
                    .matcher(clauseAfter(prompt, separable.end())).find()) {
                hits.add(index);
            }
        }
        for (int index : hits) {
            if (clauseAfter(prompt, index).endsWith("?")) {
                continue; // "Was schließen wir aus?" asks, it never orders
            }
            if (!negatedInClause(prompt, index)) {
                return true; // ONE unnegated mention is an order
            }
        }
        return false;
    }

    /** The rest of the hit's clause INCLUDING its terminator (or the prompt end). */
    private static String clauseAfter(String prompt, int from) {
        for (int i = from; i < prompt.length(); i++) {
            char c = prompt.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == ';') {
                return prompt.substring(from, i + 1);
            }
        }
        return prompt.substring(from);
    }

    /** The move guard's clause-bounded negation window, shared verbatim by the exclude guard. */
    private static boolean negatedInClause(String prompt, int index) {
        String window = prompt.substring(Math.max(0, index - 28), index);
        for (char delimiter : new char[] {'.', '!', '?', ';'}) {
            int cut = window.lastIndexOf(delimiter);
            if (cut >= 0) {
                window = window.substring(cut + 1);
            }
        }
        for (String negation : NEGATIONS) {
            if (window.contains(negation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String prompt, String[] needles) {
        for (String needle : needles) {
            if (prompt.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
