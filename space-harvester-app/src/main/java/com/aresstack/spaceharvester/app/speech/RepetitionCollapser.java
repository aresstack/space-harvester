package com.aresstack.spaceharvester.app.speech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collapse greedy-decoding loops such as "den den den ..." that Whisper Tiny emits when a window
 * contains silence or a cut-off word. A phrase of up to {@value #MAXIMUM_PHRASE_WORDS} words that
 * repeats at least {@value #MINIMUM_REPETITIONS} times in a row is kept exactly once.
 */
final class RepetitionCollapser {
    private static final int MAXIMUM_PHRASE_WORDS = 12;
    private static final int MINIMUM_REPETITIONS = 3;

    private RepetitionCollapser() {
    }

    static String collapse(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        List<String> words = List.of(text.strip().split("\\s+"));
        List<String> collapsed = collapseOnce(words);
        while (collapsed.size() < words.size()) {
            words = collapsed;
            collapsed = collapseOnce(words);
        }
        return String.join(" ", collapsed);
    }

    private static List<String> collapseOnce(List<String> words) {
        List<String> keys = new ArrayList<>(words.size());
        for (String word : words) {
            keys.add(key(word));
        }
        List<String> result = new ArrayList<>(words.size());
        int index = 0;
        while (index < words.size()) {
            int skipped = 0;
            for (int length = 1; length <= MAXIMUM_PHRASE_WORDS && skipped == 0; length++) {
                int repetitions = repetitionsAt(keys, index, length);
                if (repetitions >= MINIMUM_REPETITIONS) {
                    result.addAll(words.subList(index, index + length));
                    skipped = length * repetitions;
                }
            }
            if (skipped == 0) {
                result.add(words.get(index));
                skipped = 1;
            }
            index += skipped;
        }
        return result;
    }

    private static int repetitionsAt(List<String> keys, int start, int length) {
        int repetitions = 1;
        while (start + (repetitions + 1) * length <= keys.size()
                && sameBlock(keys, start, start + repetitions * length, length)) {
            repetitions++;
        }
        return repetitions;
    }

    private static boolean sameBlock(List<String> keys, int first, int second, int length) {
        for (int offset = 0; offset < length; offset++) {
            if (!keys.get(first + offset).equals(keys.get(second + offset))) {
                return false;
            }
        }
        return true;
    }

    /** Compare words without case and surrounding punctuation; pure punctuation compares as-is. */
    private static String key(String word) {
        String stripped = word.replaceAll("^\\p{Punct}+|\\p{Punct}+$", "").toLowerCase(Locale.ROOT);
        return stripped.isEmpty() ? word : stripped;
    }
}
