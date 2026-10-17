package com.aresstack.spaceharvester.nlp.extraction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wie oft und wo eine Grundform (sonst Wortform) als grammatisches Subjekt oder Akkusativobjekt vorkommt. Gezählt
 * werden nur Parserkanten, keine Pronomen (ihr Bezug ist offen) und nichts wird zusammengeführt: hinter einem
 * Schlüssel stehen einzelne Erwähnungen, die verschiedene Personen oder Dinge sein können. Eine sprachliche
 * Statistik, keine fachliche Wichtigkeit.
 */
public final class MentionStatistics {

    private MentionStatistics() {
    }

    public static List<TermCount> of(ObjectCentricAnalysis analysis) {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (Statement statement : analysis.statements()) {
            for (Argument argument : statement.arguments()) {
                Function function = function(argument);
                if (function == null || argument.basis() != Basis.PARSER) {
                    continue;
                }
                Mention mention = analysis.mention(argument.mention());
                if (mention.resolution() == Resolution.UNRESOLVED) {
                    continue;
                }
                byKey.computeIfAbsent(function + "\u0000" + mention.key(), key -> new ArrayList<>()).add(mention.id());
            }
        }
        List<TermCount> counts = new ArrayList<>();
        byKey.forEach((key, mentions) -> {
            String[] parts = key.split("\u0000", 2);
            counts.add(new TermCount(parts[1], Function.valueOf(parts[0]), mentions));
        });
        counts.sort(Comparator.comparingInt(TermCount::count).reversed().thenComparing(TermCount::key));
        return counts;
    }

    private static Function function(Argument argument) {
        return switch (argument.relation()) {
            case "nsubj" -> Function.SUBJECT;
            case "nsubj:pass" -> Function.PASSIVE_SUBJECT;
            case "obj", "obj+conj" -> Function.OBJECT;
            default -> null;
        };
    }

    public enum Function {
        SUBJECT,
        PASSIVE_SUBJECT,
        OBJECT
    }

    /**
     * @param key      Grundform oder Wortform des Kopfs
     * @param function grammatische Funktion
     * @param mentions IDs der einzelnen Erwähnungen (Fundstellen)
     */
    public record TermCount(String key, Function function, List<String> mentions) {

        public TermCount {
            mentions = List.copyOf(mentions);
        }

        public int count() {
            return mentions.size();
        }
    }
}
