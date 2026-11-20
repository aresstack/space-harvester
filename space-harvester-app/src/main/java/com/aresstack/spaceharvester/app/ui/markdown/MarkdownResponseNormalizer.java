package com.aresstack.spaceharvester.app.ui.markdown;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entfernt eine einzelne äußere Markdown-Umhüllung einer Modellantwort (aus askai-java8
 * {@code MarkdownResponseNormalizer}).
 *
 * <p>Modelle liefern ihre ganze Antwort häufig als einen Zaunblock mit der Sprache {@code markdown} (oder
 * {@code md}/{@code commonmark}/{@code gfm}). Unverändert rendert Flexmark das korrekt als Codeblock — dicht,
 * ohne Umbruch, mit Sprachmarke —, sodass Überschriften, Listen und innere Zäune nie dargestellt würden. Dieser
 * Normalisierer entfernt nur diesen äußeren Behälter, damit der Inhalt als echtes Markdown geparst wird.</p>
 *
 * <p>Bewusst konservativ: Der äußere Zaun fällt nur, wenn die gesamte nicht leere Antwort genau ein umschließender
 * Zaun einer erlaubten Sprache ist und nach dem schließenden Zaun nichts mehr steht. Ein echter Codeblock
 * ({@code java}, {@code json}, {@code mermaid}, …) oder eine Antwort mit nachfolgendem Text bleibt unverändert.
 * Während des Streamings (noch kein schließender Zaun) wird der noch offene äußere Behälter vorläufig entfernt,
 * damit die Antwort live gerendert wird; der strenge Durchgang läuft bei {@code finishStreaming()}.</p>
 */
final class MarkdownResponseNormalizer {

    private static final Set<String> CONTAINER_LANGUAGES = containerLanguages();
    // Ein öffnender Zaun: bis zu 3 führende Leerzeichen, 3+ Backticks oder Tilden, optional ein Wort als Sprache.
    private static final Pattern OPEN_FENCE =
            Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*([^\\s`~]*)\\s*$");

    private MarkdownResponseNormalizer() {
    }

    /**
     * @param raw      der bisher empfangene Antworttext
     * @param complete {@code true} am Ende (streng: ein echter einzelner umschließender Zaun ist nötig);
     *                 {@code false} während des Streamings (nachsichtig: ein offener äußerer Behälter fällt)
     * @return die Antwort ohne einzelne äußere Markdown-Umhüllung, sonst {@code raw} unverändert
     */
    static String normalize(String raw, boolean complete) {
        if (raw == null) {
            return "";
        }
        String[] lines = raw.split("\n", -1);

        int firstIndex = firstNonBlank(lines);
        if (firstIndex < 0) {
            return raw;
        }
        Matcher opener = OPEN_FENCE.matcher(lines[firstIndex]);
        if (!opener.matches()) {
            return raw;
        }
        String marker = opener.group(1);
        String language = opener.group(2).toLowerCase(java.util.Locale.ROOT);
        if (!CONTAINER_LANGUAGES.contains(language)) {
            return raw; // nur Markdown-Behälter auspacken, nie einen echten Codeblock
        }

        int lastIndex = lastNonBlank(lines);
        if (closesEarly(lines, firstIndex, lastIndex, marker)) {
            // Space Harvester (Codex-Befund zu #966): Der Behälter schließt vor dem Ende, danach folgen Text oder
            // weitere Blöcke; das ist kein einzelner umschließender Zaun, die Antwort bleibt, wie sie ist.
            return raw;
        }
        boolean closed = lastIndex > firstIndex && isBareClose(lines[lastIndex], marker);
        if (closed) {
            return join(lines, firstIndex + 1, lastIndex);
        }
        if (complete) {
            return raw; // kein echter einzelner umschließender Zaun — Antwort unverändert lassen
        }
        return join(lines, firstIndex + 1, lines.length); // Streaming: die noch offene Behälterzeile fällt
    }

    /**
     * Ob der Behälter schon vor der letzten Zeile schließt: ein schließender Zaun auf oberster Ebene zwischen Öffnung
     * und Ende. Innere Blöcke mit Sprache zählen als geöffnet, bis ihr eigener Zaun sie schließt.
     */
    private static boolean closesEarly(String[] lines, int firstIndex, int lastIndex, String marker) {
        int depth = 0;
        for (int i = firstIndex + 1; i < lastIndex; i++) {
            Matcher inner = OPEN_FENCE.matcher(lines[i]);
            if (inner.matches() && inner.group(2).length() > 0) {
                depth++;
            } else if (isBareClose(lines[i], marker)) {
                if (depth == 0) {
                    return true;
                }
                depth--;
            }
        }
        return false;
    }

    private static int firstNonBlank(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().length() > 0) {
                return i;
            }
        }
        return -1;
    }

    private static int lastNonBlank(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].trim().length() > 0) {
                return i;
            }
        }
        return -1;
    }

    /** Ein schließender Zaun hat dasselbe Zeichen, ist mindestens so lang und trägt keine Sprache. */
    private static boolean isBareClose(String line, String openMarker) {
        String trimmed = line.trim();
        char markerChar = openMarker.charAt(0);
        int minLength = openMarker.length();
        if (trimmed.length() < minLength) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != markerChar) {
                return false;
            }
        }
        return true;
    }

    private static String join(String[] lines, int fromInclusive, int toExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (i > fromInclusive) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    private static Set<String> containerLanguages() {
        Set<String> languages = new HashSet<String>();
        languages.add("markdown");
        languages.add("md");
        languages.add("commonmark");
        languages.add("gfm");
        return languages;
    }
}
