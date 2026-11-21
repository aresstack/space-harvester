package com.aresstack.spaceharvester.application.quality;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Die lokale Ergebnisdatei ({@code .tsv}, eine Zeile je Frage) schreiben und für Vergleich und Fortsetzen wieder
 * lesen. Nur diese Datei enthält Wortlaut und Titel; sie liegt neben dem Testbestand.
 */
public final class QualityResults {

    static final List<String> COLUMNS = List.of("id", "art", "erwartet", "raenge", "lokal", "bester_rang", "top5",
            "top_kontext", "top10", "recall10", "dauer_ms", "erster_fund_ms", "abschluss", "varianten",
            "quellen_aus", "treffer", "bester_wert", "brauchbar_kontext", "fehler", "gefunden", "titel", "frage");

    /**
     * Gelesene Ergebnisdatei.
     *
     * @param info    Kopf
     * @param results Fragen
     * @param errors  unlesbare Zeilen
     */
    public record Stored(RunInfo info, List<CaseResult> results, List<QualitySuite.LineError> errors) {

        public Stored {
            results = List.copyOf(results);
            errors = List.copyOf(errors);
        }
    }

    private QualityResults() {
    }

    public static String write(RunInfo info, List<CaseResult> results) {
        StringBuilder out = new StringBuilder();
        out.append("# Suchqualität (Q1), Ergebnis; enthält Wortlaut und Titel, bleibt lokal\n");
        out.append("# version=").append(info.version()).append('\n');
        out.append("# profil=").append(info.profile()).append('\n');
        out.append("# start=").append(info.started()).append('\n');
        out.append("# testbestand=").append(info.suite()).append('\n');
        out.append("# kontext=").append(info.contextSize()).append('\n');
        out.append("# schwelle=").append(number(info.relevantScore())).append('\n');
        out.append("# fragen=").append(info.total()).append('\n');
        out.append("# abgebrochen=").append(info.cancelled() ? "ja" : "nein").append('\n');
        out.append(String.join("\t", COLUMNS)).append('\n');
        for (CaseResult r : results) {
            List<String> cells = new ArrayList<>();
            cells.add(r.id());
            cells.add(String.join(",", r.kinds().stream().sorted().map(CaseKind::label).toList()));
            cells.add(join(r.expected().stream().map(PageRef::toString).toList()));
            cells.add(join(r.ranks().stream().map(rank -> rank == 0 ? "-" : Integer.toString(rank)).toList()));
            cells.add(join(r.stored().stream().map(s -> s ? "j" : "n").toList()));
            cells.add(r.bestRank() == 0 ? "-" : Integer.toString(r.bestRank()));
            cells.add(flag(r.inTop(5)));
            cells.add(flag(r.inTop(info.contextSize())));
            cells.add(flag(r.inTop(10)));
            cells.add(number(r.recallTop10()));
            cells.add(Long.toString(r.durationMillis()));
            cells.add(Long.toString(r.firstExpectedMillis()));
            cells.add(RunInfo.clean(r.completion()));
            cells.add(Integer.toString(r.variants()));
            cells.add(Integer.toString(r.unavailable()));
            cells.add(Integer.toString(r.hits()));
            cells.add(Double.isNaN(r.bestScore()) ? "-" : number(r.bestScore()));
            cells.add(Integer.toString(r.usefulInContext()));
            cells.add(r.failure() == null ? "" : RunInfo.clean(r.failure()));
            cells.add(join(r.found().stream().map(PageRef::toString).toList()));
            cells.add(join(r.titles().stream().map(t -> RunInfo.clean(t).replace('|', '¦')).toList()));
            cells.add(RunInfo.clean(r.question()));
            out.append(String.join("\t", cells)).append('\n');
        }
        return out.toString();
    }

    public static Stored read(String text) {
        Map<String, String> meta = new HashMap<>();
        Map<String, Integer> columns = null;
        List<CaseResult> results = new ArrayList<>();
        List<QualitySuite.LineError> errors = new ArrayList<>();
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    meta.put(line.substring(1, eq).strip(), line.substring(eq + 1).strip());
                }
                continue;
            }
            String[] cells = line.split("\t", -1);
            if (columns == null) {
                columns = new HashMap<>();
                for (int c = 0; c < cells.length; c++) {
                    columns.put(cells[c].strip(), c);
                }
                if (!columns.containsKey("id") || !columns.containsKey("raenge")) {
                    errors.add(new QualitySuite.LineError(i + 1, "keine Ergebnisdatei der Suchqualität"));
                    break;
                }
                continue;
            }
            try {
                results.add(row(cells, columns));
            } catch (RuntimeException invalid) {
                errors.add(new QualitySuite.LineError(i + 1, "Zeile nicht lesbar"));
            }
        }
        Instant started;
        try {
            started = Instant.parse(meta.getOrDefault("start", "1970-01-01T00:00:00Z"));
        } catch (DateTimeParseException invalid) {
            started = Instant.EPOCH;
        }
        RunInfo info = new RunInfo(meta.get("version"), meta.get("profil"), started, meta.get("testbestand"),
                integer(meta.getOrDefault("kontext", "8")), decimal(meta.getOrDefault("schwelle", "0")),
                integer(meta.getOrDefault("fragen", Integer.toString(results.size()))),
                "ja".equals(meta.get("abgebrochen")));
        return new Stored(info, results, errors);
    }

    private static CaseResult row(String[] cells, Map<String, Integer> columns) {
        Set<CaseKind> kinds = EnumSet.noneOf(CaseKind.class);
        for (String kind : cell(cells, columns, "art").split(",")) {
            if (!kind.isBlank()) {
                kinds.add(CaseKind.parse(kind).orElseThrow());
            }
        }
        if (kinds.isEmpty()) {
            kinds.add(CaseKind.EINFACH);
        }
        List<PageRef> expected = pages(cell(cells, columns, "erwartet"));
        List<Integer> ranks = new ArrayList<>();
        for (String rank : split(cell(cells, columns, "raenge"))) {
            ranks.add(rank.equals("-") ? 0 : Integer.parseInt(rank));
        }
        List<Boolean> stored = new ArrayList<>();
        for (String flag : split(cell(cells, columns, "lokal"))) {
            stored.add(flag.equals("j"));
        }
        String score = cell(cells, columns, "bester_wert");
        String failure = cell(cells, columns, "fehler");
        List<String> titles = new ArrayList<>(split(cell(cells, columns, "titel")));
        return new CaseResult(cell(cells, columns, "id"), kinds, expected, ranks, stored,
                Long.parseLong(cell(cells, columns, "dauer_ms")), Long.parseLong(cell(cells, columns,
                "erster_fund_ms")), cell(cells, columns, "abschluss"), integer(cell(cells, columns, "varianten")),
                integer(cell(cells, columns, "quellen_aus")), integer(cell(cells, columns, "treffer")),
                score.isEmpty() || score.equals("-") ? Double.NaN : decimal(score),
                integer(cell(cells, columns, "brauchbar_kontext")), failure.isEmpty() ? null : failure,
                pages(cell(cells, columns, "gefunden")), cell(cells, columns, "frage"), titles);
    }

    private static List<PageRef> pages(String text) {
        List<PageRef> pages = new ArrayList<>();
        for (String token : split(text)) {
            int colon = token.indexOf(':');
            pages.add(new PageRef(token.substring(0, colon), token.substring(colon + 1)));
        }
        return pages;
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        if (text.isEmpty()) {
            return parts;
        }
        for (String part : text.split("\\|", -1)) {
            parts.add(part.strip());
        }
        return parts;
    }

    private static String cell(String[] cells, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= cells.length ? "" : cells[index].strip();
    }

    private static String join(List<String> parts) {
        return String.join("|", parts);
    }

    private static String flag(boolean value) {
        return value ? "ja" : "nein";
    }

    static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static int integer(String value) {
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    private static double decimal(String value) {
        return Double.parseDouble(value);
    }
}
