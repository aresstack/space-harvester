package com.aresstack.spaceharvester.application.quality;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ein gelesener Testbestand ({@code fragen.tsv}, Format in {@code docs/suchqualitaet/README.md}): die gültigen Fragen
 * und je ungültige Zeile ein Fehler mit Nummer. Eine fehlerhafte Zeile bricht das Lesen nicht ab; der Lauf misst die
 * übrigen.
 *
 * @param cases  gültige Fragen in Dateireihenfolge
 * @param errors Zeilenfehler in Dateireihenfolge
 */
public record QualitySuite(List<QualityCase> cases, List<LineError> errors) {

    /** Längster Wortlaut einer Frage, wie bei der Suche ({@code KeywordSearch.MAX_TEXT_LENGTH}). */
    public static final int MAX_QUESTION_LENGTH = 200;

    static final List<String> REQUIRED = List.of("id", "frage", "bereich", "erwartet");

    /**
     * Ein Fehler in einer Zeile; die Meldung nennt nie den Wortlaut der Frage.
     *
     * @param line    Zeile ab 1
     * @param message Grund
     */
    public record LineError(int line, String message) {

        @Override
        public String toString() {
            return "Zeile " + line + ": " + message;
        }
    }

    public QualitySuite {
        cases = List.copyOf(cases);
        errors = List.copyOf(errors);
    }

    /** Liest eine Datei: UTF-8 (mit oder ohne BOM) oder UTF-16 mit BOM (Excel „Unicode-Text“). */
    public static QualitySuite read(byte[] content, PageResolver resolver) {
        return parse(decode(content), resolver);
    }

    static String decode(byte[] content) {
        if (content.length >= 2 && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xfe) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
        }
        if (content.length >= 2 && (content[0] & 0xff) == 0xfe && (content[1] & 0xff) == 0xff) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE);
        }
        Charset utf8 = StandardCharsets.UTF_8;
        if (content.length >= 3 && (content[0] & 0xff) == 0xef && (content[1] & 0xff) == 0xbb
                && (content[2] & 0xff) == 0xbf) {
            return new String(content, 3, content.length - 3, utf8);
        }
        return new String(content, utf8);
    }

    /** Liest den Text einer Datei. */
    public static QualitySuite parse(String text, PageResolver resolver) {
        List<QualityCase> cases = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        Map<String, Integer> columns = null;
        Set<String> ids = new HashSet<>();
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int number = i + 1;
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            if (columns == null) {
                columns = header(cells);
                List<String> missing = new ArrayList<>();
                for (String name : REQUIRED) {
                    if (!columns.containsKey(name)) {
                        missing.add(name);
                    }
                }
                if (!missing.isEmpty()) {
                    errors.add(new LineError(number, "Spaltenköpfe fehlen: " + String.join(", ", missing)
                            + " (Tabulator als Trenner?)"));
                    return new QualitySuite(cases, errors);
                }
                continue;
            }
            try {
                QualityCase parsed = row(number, cells, columns, resolver);
                if (!ids.add(parsed.id())) {
                    throw new RowException("ID " + parsed.id() + " kommt doppelt vor");
                }
                cases.add(parsed);
            } catch (RowException invalid) {
                errors.add(new LineError(number, invalid.getMessage()));
            }
        }
        if (columns == null) {
            errors.add(new LineError(1, "Datei enthält keine Spaltenköpfe"));
        }
        return new QualitySuite(cases, errors);
    }

    private static Map<String, Integer> header(String[] cells) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            columns.putIfAbsent(cells[i].strip().toLowerCase(Locale.ROOT), i);
        }
        return columns;
    }

    private static String cell(String[] cells, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= cells.length) {
            return "";
        }
        String value = cells[index].strip();
        // Excel setzt Zellen mit Sonderzeichen in Anführungszeichen.
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).replace("\"\"", "\"").strip();
        }
        return value;
    }

    private static QualityCase row(int number, String[] cells, Map<String, Integer> columns, PageResolver resolver) {
        String id = cell(cells, columns, "id");
        if (id.isEmpty()) {
            throw new RowException("ID fehlt");
        }
        if (!id.matches("[\\p{L}\\p{N}._-]{1,40}")) {
            throw new RowException("ID darf nur Buchstaben, Ziffern, Punkt, Strich und Unterstrich enthalten (höchstens 40)");
        }
        String question = cell(cells, columns, "frage").replaceAll("\\s+", " ");
        if (question.isEmpty()) {
            throw new RowException("Frage fehlt (" + id + ")");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new RowException("Frage hat mehr als " + MAX_QUESTION_LENGTH + " Zeichen (" + id + ")");
        }
        Set<CaseKind> kinds = EnumSet.noneOf(CaseKind.class);
        for (String token : cell(cells, columns, "art").split(",")) {
            if (token.isBlank()) {
                continue;
            }
            kinds.add(CaseKind.parse(token).orElseThrow(() -> new RowException("unbekannte Art „" + token.strip()
                    + "“ (" + id + "), erlaubt: einfach, verteilt, akronym, synonym, keine")));
        }
        if (kinds.isEmpty()) {
            kinds.add(CaseKind.EINFACH);
        }
        List<ScopeSpec> scope = new ArrayList<>();
        for (String token : cell(cells, columns, "bereich").split("\\|")) {
            if (!token.isBlank()) {
                scope.add(scope(token.strip(), id, resolver));
            }
        }
        if (scope.isEmpty()) {
            throw new RowException("Bereich fehlt (" + id + "), für alles „*“");
        }
        List<PageRef> expected = new ArrayList<>();
        for (String token : cell(cells, columns, "erwartet").split("\\|")) {
            if (!token.isBlank()) {
                PageRef page = page(token.strip(), id, resolver);
                if (!expected.contains(page)) {
                    expected.add(page);
                }
            }
        }
        if (kinds.contains(CaseKind.KEINE) && !expected.isEmpty()) {
            throw new RowException("Art „keine“ darf keine erwartete Fundstelle haben (" + id + ")");
        }
        if (!kinds.contains(CaseKind.KEINE) && expected.isEmpty()) {
            throw new RowException("erwartete Fundstelle fehlt (" + id + "); steht die Antwort nirgends, Art „keine“");
        }
        return new QualityCase(number, id, question, scope, expected, kinds);
    }

    private static ScopeSpec scope(String token, String id, PageResolver resolver) {
        boolean children = false;
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.endsWith("+unterseiten")) {
            children = true;
            token = token.substring(0, token.length() - "+unterseiten".length()).strip();
        }
        ScopeSpec spec;
        if (token.equals("*")) {
            spec = ScopeSpec.all();
        } else if (isUrl(token)) {
            spec = ScopeSpec.page(resolve(token, id, resolver), children);
        } else {
            String[] parts = token.split(":", 3);
            if (parts[0].isBlank() || (parts.length > 1 && parts[1].isBlank())) {
                throw new RowException("Bereich unvollständig (" + id + ")");
            }
            if (parts.length == 3 && parts[1].equalsIgnoreCase("seite") && !parts[2].isBlank()) {
                spec = ScopeSpec.page(new PageRef(parts[0], parts[2]), children);
            } else if (parts.length == 2) {
                spec = ScopeSpec.space(parts[0], parts[1]);
            } else if (parts.length == 1) {
                spec = ScopeSpec.source(parts[0]);
            } else {
                throw new RowException("Bereich nicht lesbar (" + id
                        + "), erlaubt: *, quelle, quelle:SPACE, quelle:seite:ID, Seitenadresse");
            }
        }
        if (children && spec.kind() != ScopeSpec.Kind.PAGE) {
            throw new RowException("„+unterseiten“ gilt nur für eine Seite (" + id + ")");
        }
        return spec;
    }

    private static PageRef page(String token, String id, PageResolver resolver) {
        if (isUrl(token)) {
            return resolve(token, id, resolver);
        }
        String[] parts = token.split(":", 3);
        if (parts.length == 3 && parts[1].equalsIgnoreCase("seite")) {
            parts = new String[] {parts[0], parts[2]};
        } else if (parts.length == 3) {
            parts = new String[] {parts[0], parts[1] + ":" + parts[2]};
        }
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new RowException("Fundstelle nicht lesbar (" + id + "), erlaubt: quelle:pageId oder Seitenadresse");
        }
        return new PageRef(parts[0].strip(), parts[1].strip());
    }

    private static boolean isUrl(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static PageRef resolve(String url, String id, PageResolver resolver) {
        Optional<PageRef> page = resolver.resolve(url);
        // Die Adresse selbst kommt nicht in die Meldung, sie kann auf Inhalte schließen lassen.
        return page.orElseThrow(() -> new RowException("Seitenadresse keiner eingetragenen Quelle zuzuordnen (" + id
                + ")"));
    }

    private static final class RowException extends RuntimeException {
        RowException(String message) {
            super(message, null, false, false);
        }
    }
}
