package com.aresstack.spaceharvester.app.ui.content;

/**
 * Wo das Vorlesen nach einem Doppelklick neu beginnt: findet den angeklickten Absatz der Anzeige im vorlesbaren Text
 * und liefert die Stelle des angeklickten Worts darin. Leerraum zählt beim Vergleich nicht (die Anzeige bricht anders
 * um als der Text); trifft der Klick nichts Vorlesbares (Bild, Leerraum, Diagramm), ist das Ergebnis -1.
 *
 * <p>Unverändert aus dem Enterprise AI Client ({@code proasteion/exedra/ui/reader/ReadAloudStart}), Angelo 23.11.2026:
 * „dass man immer doppelt klicken konnte auf ein Wort … und dann hat er von dort weitergelesen“.
 */
final class ReadAloudStart {

    /** So viele Zeichen ab dem angeklickten Wort genügen, wenn der ganze Absatz nicht wörtlich vorkommt. */
    private static final int TAIL_PROBE = 40;

    private ReadAloudStart() {
    }

    /**
     * @param spoken    der vorlesbare Text (wie er an die Sprachausgabe geht)
     * @param paragraph der angezeigte Absatz um den Klick
     * @param offset    die Klickposition im Absatz
     * @return der Index in {@code spoken}, ab dem vorgelesen wird, oder -1
     */
    static int find(String spoken, String paragraph, int offset) {
        return find(spoken, paragraph, offset, null);
    }

    /**
     * Wo der angeklickte Absatz in der Anzeige steht, wenn sein Text mehrmals vorkommt (etwa eine Überschrift auch im
     * Inhaltsverzeichnis).
     *
     * @param occurrence der wievielte gleichlautende Absatz der Anzeige es ist, ab 0
     * @param total      wie oft er in der Anzeige vorkommt
     * @param share      wie weit er in der Anzeige steht, 0 am Anfang, 1 am Ende
     */
    record Place(int occurrence, int total, double share) {
    }

    /**
     * Die Stelle des angeklickten Absatzes in der Anzeige: der wievielte gleichlautende er ist und wie weit vorn er
     * steht.
     *
     * @param shown          der ganze angezeigte Text
     * @param paragraphStart wo der angeklickte Absatz darin beginnt
     * @param paragraph      der angeklickte Absatz
     */
    static Place place(String shown, int paragraphStart, String paragraph) {
        String needle = Collapsed.of(paragraph).text.trim();
        if (needle.isEmpty()) {
            return null;
        }
        String before = Collapsed.of(shown.substring(0, Math.max(0, Math.min(paragraphStart, shown.length())))).text;
        return new Place(count(before, needle), count(Collapsed.of(shown).text, needle),
                shown.isEmpty() ? 0 : (double) paragraphStart / shown.length());
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Wie {@link #find(String, String, int)}; kommt der Absatz im vorlesbaren Text mehrmals vor, nimmt es dasselbe
     * Vorkommen wie in der Anzeige (gleich viele) oder sonst das, das anteilig am nächsten an der Klickstelle steht.
     * Ohne das begann ein Doppelklick auf eine Überschrift, die auch im Inhaltsverzeichnis steht, beim Verzeichnis.
     */
    static int find(String spoken, String paragraph, int offset, Place place) {
        if (spoken == null || paragraph == null || offset < 0 || offset > paragraph.length()) {
            return -1;
        }
        int word = wordStart(paragraph, offset);
        if (word < 0) {
            return -1;
        }
        Collapsed text = Collapsed.of(spoken);
        Collapsed shown = Collapsed.of(paragraph);
        int local = shown.collapsedIndex(word);
        String needle = shown.text.trim();
        int lead = shown.text.indexOf(needle);
        int at = needle.isEmpty() ? -1 : pick(text.text, needle, place);
        if (at >= 0) {
            return text.original(at + local - lead);
        }
        String tail = shown.text.substring(local).trim();
        if (tail.length() > TAIL_PROBE) {
            tail = tail.substring(0, TAIL_PROBE).trim();
        }
        at = tail.isEmpty() ? -1 : pick(text.text, tail, place);
        return at < 0 ? -1 : text.original(at);
    }

    /** Das passende Vorkommen von {@code needle} in {@code text}; ohne Angabe zur Stelle das erste. */
    private static int pick(String text, String needle, Place place) {
        java.util.List<Integer> found = new java.util.ArrayList<>();
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found.add(at);
        }
        if (found.isEmpty()) {
            return -1;
        }
        if (place == null || found.size() == 1) {
            return found.get(0);
        }
        if (found.size() == place.total() && place.occurrence() < found.size()) {
            return found.get(place.occurrence());
        }
        double wanted = place.share() * text.length();
        return found.stream().min(java.util.Comparator.comparingDouble(at -> Math.abs(at - wanted))).orElseThrow();
    }

    /** Anfang des Worts an {@code offset}; -1, wenn dort (und direkt davor) kein Buchstabe und keine Ziffer steht. */
    private static int wordStart(String paragraph, int offset) {
        int at = offset;
        if (at >= paragraph.length() || !Character.isLetterOrDigit(paragraph.charAt(at))) {
            at--; // Klick hinter das letzte Zeichen eines Worts
        }
        if (at < 0 || at >= paragraph.length() || !Character.isLetterOrDigit(paragraph.charAt(at))) {
            return -1;
        }
        while (at > 0 && Character.isLetterOrDigit(paragraph.charAt(at - 1))) {
            at--;
        }
        return at;
    }

    /** Ein Text mit jedem Leerraum-Lauf als einem Leerzeichen und der Rückabbildung auf die Originalstellen. */
    static final class Collapsed {
        final String text;
        private final int[] originals;

        private Collapsed(String text, int[] originals) {
            this.text = text;
            this.originals = originals;
        }

        static Collapsed of(String source) {
            StringBuilder text = new StringBuilder(source.length());
            int[] originals = new int[source.length() + 1];
            boolean space = false;
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                    if (!space) {
                        originals[text.length()] = i;
                        text.append(' ');
                        space = true;
                    }
                } else {
                    originals[text.length()] = i;
                    text.append(c);
                    space = false;
                }
            }
            originals[text.length()] = source.length();
            return new Collapsed(text.toString(), originals);
        }

        /** Stelle im zusammengezogenen Text zur Originalstelle {@code index}. */
        int collapsedIndex(int index) {
            for (int i = 0; i < text.length(); i++) {
                if (originals[i] >= index) {
                    return i;
                }
            }
            return text.length();
        }

        int original(int collapsed) {
            return originals[Math.max(0, Math.min(collapsed, text.length()))];
        }
    }
}
