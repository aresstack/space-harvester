package com.aresstack.spaceharvester.application.quality;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Der Bericht eines Laufs als Markdown: nur Fragen-IDs und Zahlen, nie Wortlaut, Titel oder Adressen; die stehen in der
 * Ergebnisdatei ({@link QualityResults}).
 */
public final class QualityReport {

    private QualityReport() {
    }

    /**
     * @param errors     Zeilenfehler des Testbestands (nur Nummer und Grund)
     * @param comparison Vergleich mit einem früheren Ergebnis oder {@code null}
     */
    public static String markdown(RunInfo info, List<CaseResult> results, List<QualitySuite.LineError> errors,
                                  QualityComparison comparison) {
        int k = info.contextSize();
        StringBuilder out = new StringBuilder();
        out.append("# Suchqualität\n\n");
        out.append("- Start: ").append(info.started()).append('\n');
        out.append("- Version: ").append(info.version()).append('\n');
        out.append("- Suchprofil: ").append(info.profile()).append('\n');
        out.append("- Testbestand: ").append(info.suite()).append('\n');
        out.append("- Gemessen: ").append(results.size()).append(" von ").append(info.total()).append(" Fragen")
                .append(info.cancelled() ? " (abgebrochen)" : "").append('\n');
        out.append("- Chat-Kontext: die ersten ").append(k).append(" Treffer; brauchbar ab Bewertung ")
                .append(QualityResults.number(info.relevantScore())).append('\n');
        if (!errors.isEmpty()) {
            out.append("- Zeilenfehler im Testbestand: ").append(errors.size()).append('\n');
            for (QualitySuite.LineError error : errors) {
                out.append("  - ").append(error).append('\n');
            }
        }

        QualitySummary all = QualitySummary.of(results, k);
        out.append("\n## Gesamt\n\n| Kennzahl | Wert |\n|---|---|\n");
        out.append("| Fragen mit Antwort | ").append(all.questions()).append(" |\n");
        out.append("| Treffer unter den ersten 5 | ").append(ratio(all.top5(), all)).append(" |\n");
        out.append("| Treffer im Chat-Kontext (erste ").append(k).append(") | ").append(ratio(all.topContext(), all))
                .append(" |\n");
        out.append("| Treffer unter den ersten 10 | ").append(ratio(all.top10(), all)).append(" |\n");
        out.append("| MRR | ").append(QualityResults.number(all.mrr())).append(" |\n");
        if (all.spread() > 0) {
            out.append("| Verteilt: Anteil Fundstellen unter den ersten 10 | ")
                    .append(percent(all.spreadRecall())).append(" (").append(all.spread()).append(" Fragen) |\n");
        }
        if (all.noAnswer() > 0) {
            out.append("| Antwort nirgends: zu Recht nichts Brauchbares | ").append(all.noAnswerCorrect())
                    .append(" von ").append(all.noAnswer()).append(" |\n");
        }
        out.append("| Gescheiterte Suchen | ").append(all.failed()).append(" |\n");
        out.append("| Laufzeit Median / 90 % / max | ").append(seconds(all.medianMillis())).append(" / ")
                .append(seconds(all.p90Millis())).append(" / ").append(seconds(all.maxMillis())).append(" |\n");

        out.append("\n## Nach Art und Bestand\n\n");
        out.append("| Gruppe | Fragen | erste 5 | Chat-Kontext | erste 10 | MRR | Median |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (CaseKind kind : CaseKind.values()) {
            if (kind != CaseKind.KEINE) {
                group(out, kind.label(), results, k, result -> result.kinds().contains(kind));
            }
        }
        group(out, "erwartete Seiten lokal", results, k, CaseResult::allStored);
        group(out, "nicht vollständig lokal", results, k, result -> !result.allStored());

        out.append("\n## Je Frage\n\n");
        out.append("| ID | Art | bester Rang | Ränge | lokal | Dauer | erster Fund | Abschluss | Hinweis |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (CaseResult r : results) {
            out.append("| ").append(r.id()).append(" | ")
                    .append(String.join(",", r.kinds().stream().sorted().map(CaseKind::label).toList()))
                    .append(" | ").append(r.noAnswer() ? "–" : r.bestRank() == 0 ? "nicht gefunden" : r.bestRank())
                    .append(" | ").append(String.join(", ", r.ranks().stream()
                            .map(rank -> rank == 0 ? "–" : Integer.toString(rank)).toList()))
                    .append(" | ").append(String.join(", ", r.stored().stream().map(s -> s ? "ja" : "nein").toList()))
                    .append(" | ").append(seconds(r.durationMillis()))
                    .append(" | ").append(r.firstExpectedMillis() < 0 ? "–" : seconds(r.firstExpectedMillis()))
                    .append(" | ").append(r.completion())
                    .append(" | ").append(hint(r)).append(" |\n");
        }

        if (comparison != null) {
            QualitySummary b = comparison.before();
            QualitySummary a = comparison.after();
            out.append("\n## Vergleich mit dem früheren Ergebnis\n\n| Kennzahl | vorher | jetzt |\n|---|---|---|\n");
            out.append("| Fragen mit Antwort | ").append(b.questions()).append(" | ").append(a.questions()).append(" |\n");
            out.append("| erste 5 | ").append(ratio(b.top5(), b)).append(" | ").append(ratio(a.top5(), a)).append(" |\n");
            out.append("| Chat-Kontext | ").append(ratio(b.topContext(), b)).append(" | ").append(ratio(a.topContext(), a))
                    .append(" |\n");
            out.append("| erste 10 | ").append(ratio(b.top10(), b)).append(" | ").append(ratio(a.top10(), a)).append(" |\n");
            out.append("| MRR | ").append(QualityResults.number(b.mrr())).append(" | ")
                    .append(QualityResults.number(a.mrr())).append(" |\n");
            out.append("| zu Recht leer | ").append(b.noAnswerCorrect()).append(" von ").append(b.noAnswer())
                    .append(" | ").append(a.noAnswerCorrect()).append(" von ").append(a.noAnswer()).append(" |\n");
            out.append("| Laufzeit Median | ").append(seconds(b.medianMillis())).append(" | ")
                    .append(seconds(a.medianMillis())).append(" |\n");
            if (comparison.changes().isEmpty()) {
                out.append("\nKeine Frage hat sich verändert.\n");
            } else {
                out.append("\n| ID | Änderung | vorher | jetzt |\n|---|---|---|---|\n");
                for (QualityComparison.Change change : comparison.changes()) {
                    out.append("| ").append(change.id()).append(" | ").append(direction(change.way())).append(" | ")
                            .append(rank(change.from())).append(" | ").append(rank(change.to())).append(" |\n");
                }
                out.append("\nRänge: Zahl = bester Rang, „–“ = nicht gefunden; bei Art „keine“ 1 = zu Recht leer.\n");
            }
        }
        return out.toString();
    }

    private static void group(StringBuilder out, String name, List<CaseResult> results, int k,
                              Predicate<CaseResult> filter) {
        QualitySummary s = QualitySummary.of(results, k, filter.and(result -> !result.noAnswer()));
        if (s.questions() == 0) {
            return;
        }
        out.append("| ").append(name).append(" | ").append(s.questions()).append(" | ").append(ratio(s.top5(), s))
                .append(" | ").append(ratio(s.topContext(), s)).append(" | ").append(ratio(s.top10(), s))
                .append(" | ").append(QualityResults.number(s.mrr())).append(" | ").append(seconds(s.medianMillis()))
                .append(" |\n");
    }

    private static String hint(CaseResult r) {
        if (r.failed()) {
            return "gescheitert: " + r.failure();
        }
        if (r.noAnswer()) {
            return r.noAnswerCorrect() ? "zu Recht leer" : r.usefulInContext() + " brauchbar wirkende Treffer";
        }
        if (r.unavailable() > 0) {
            return r.unavailable() + " Quelle(n) ohne Antwort";
        }
        return "";
    }

    private static String direction(QualityComparison.Direction way) {
        return switch (way) {
            case BETTER -> "besser";
            case WORSE -> "schlechter";
            case NEW -> "neu";
            case REMOVED -> "entfallen";
        };
    }

    private static String rank(int value) {
        return value < 0 ? "nicht gemessen" : value == 0 ? "–" : Integer.toString(value);
    }

    private static String ratio(int count, QualitySummary summary) {
        return count + " von " + summary.questions() + " (" + percent(summary.share(count)) + ")";
    }

    private static String percent(double share) {
        return String.format(Locale.GERMANY, "%.0f %%", share * 100);
    }

    private static String seconds(long millis) {
        return String.format(Locale.GERMANY, "%.1f s", millis / 1000.0);
    }
}
