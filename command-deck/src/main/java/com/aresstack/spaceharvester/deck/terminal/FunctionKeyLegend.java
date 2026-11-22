package com.aresstack.spaceharvester.deck.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Belegung der PF-Tasten aus den letzten beiden Bildzeilen, unverändert übernommen aus MainframeMate
 * (TerminalConnectionTab.refreshFkeyLegend): erst das Natural-Format mit Tasten in einer Zeile ({@code PF1---PF2---})
 * und den Bezeichnungen spaltengenau darunter, sonst die Inline-Formate {@code F3=Exit}, {@code PF3:End},
 * {@code 10-Actions} und {@code PF1/PF13:Help}.
 */
public final class FunctionKeyLegend {

    /** Inline: Gruppe 1 = Tastennummer, Gruppe 2 = Bezeichnung (muss mit einem Buchstaben beginnen). */
    private static final Pattern FKEY_PATTERN = Pattern.compile(
            "(?:PF|F)?(\\d{1,2})\\s*[=:\\-]\\s*([A-Za-z][\\w./#@]*)");

    /** Zwei Tasten mit derselben Bezeichnung: {@code PF1/PF13:Help}. */
    private static final Pattern FKEY_MULTI_PATTERN = Pattern.compile(
            "(?:PF|F)?(\\d{1,2})/(?:PF|F)?(\\d{1,2})\\s*[=:\\-]\\s*([A-Za-z][\\w./#@]*)");

    private static final Pattern NATURAL_FKEY_LINE = Pattern.compile("PF\\d+---");
    private static final Pattern NATURAL_PF_KEY = Pattern.compile("PF(\\d{1,2})");
    private static final Pattern WORD = Pattern.compile("\\S+");

    private FunctionKeyLegend() {
    }

    /** Belegung aus dem ganzen Bild (Zeilen durch {@code \n} getrennt); liest die letzten beiden Zeilen. */
    public static Map<Integer, String> ofScreen(String screen) {
        String[] lines = screen.split("\n", -1);
        int last = lines.length;
        while (last > 0 && lines[last - 1].isEmpty()) {
            last--;
        }
        if (last == 0) {
            return Map.of();
        }
        String line2 = lines[last - 1];
        String line1 = last >= 2 ? lines[last - 2] : "";
        return parse(line1, line2);
    }

    /** Belegung PF-Nummer → Bezeichnung, in der gefundenen Reihenfolge. */
    public static Map<Integer, String> parse(String line1, String line2) {
        Map<Integer, String> parsed = new LinkedHashMap<>();
        if (NATURAL_FKEY_LINE.matcher(line1).find()) {
            parseNatural(line1, line2, parsed);
        } else if (NATURAL_FKEY_LINE.matcher(line2).find()) {
            parseNatural(line2, line1, parsed);
        }
        if (parsed.isEmpty()) {
            String trimmed = (line1 + line2).trim();
            Matcher multi = FKEY_MULTI_PATTERN.matcher(trimmed);
            while (multi.find()) {
                String label = multi.group(3);
                int first = Integer.parseInt(multi.group(1));
                int second = Integer.parseInt(multi.group(2));
                if (first >= 1 && first <= 24) {
                    parsed.put(first, label);
                }
                if (second >= 1 && second <= 24) {
                    parsed.put(second, label);
                }
            }
            Matcher single = FKEY_PATTERN.matcher(trimmed);
            while (single.find()) {
                int number = Integer.parseInt(single.group(1));
                if (number >= 1 && number <= 24 && !parsed.containsKey(number)) {
                    parsed.put(number, single.group(2));
                }
            }
        }
        return Collections.unmodifiableMap(parsed);
    }

    /** Jede Bezeichnung gehört zur Taste, in deren Spaltenbereich sie beginnt. */
    private static void parseNatural(String keysLine, String labelsLine, Map<Integer, String> parsed) {
        Matcher keyMatcher = NATURAL_PF_KEY.matcher(keysLine);
        List<int[]> keys = new ArrayList<>();
        while (keyMatcher.find()) {
            int number = Integer.parseInt(keyMatcher.group(1));
            if (number >= 1 && number <= 24) {
                keys.add(new int[] {number, keyMatcher.start()});
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        Matcher words = WORD.matcher(labelsLine);
        while (words.find()) {
            int column = words.start();
            for (int i = 0; i < keys.size(); i++) {
                int zoneStart = keys.get(i)[1];
                int zoneEnd = i + 1 < keys.size() ? keys.get(i + 1)[1] : labelsLine.length();
                if (column >= zoneStart && column < zoneEnd) {
                    parsed.put(keys.get(i)[0], words.group());
                    break;
                }
            }
        }
    }
}
