package com.aresstack.spaceharvester.app.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Wächter (Muster aus askai, {@code RawColorGuardTest}): In der App steht kein Farbwert fest im Code, jede Farbe ist
 * ein Token ({@link AppColors}). Gesucht werden Farben aus lauter Zahlen ({@code new Color(0xRRGGBB)},
 * {@code new Color(r, g, b[, a])}) und die {@code Color}-Konstanten. Ausnahme: voll durchsichtige Farben (Alpha 0),
 * sie sind keine Farbe, die ein Theme ändern kann. Farben, die zur Laufzeit aus anderen Farben berechnet werden
 * ({@code new Color(base.getRed(), …)}), sind keine Festwerte und werden nicht gemeldet.
 */
class RawColorGuardTest {

    private static final String NUMBER = "(?:0x[0-9A-Fa-f]+|\\d+)";
    private static final Pattern LITERAL = Pattern.compile(
            "new (?:java\\.awt\\.)?Color\\(\\s*" + NUMBER + "(?:\\s*,\\s*(" + NUMBER + "))?(?:\\s*,\\s*" + NUMBER
                    + ")?(?:\\s*,\\s*(" + NUMBER + "))?\\s*\\)");
    private static final Pattern CONSTANT = Pattern.compile(
            "(?<![\\w.])(?:java\\.awt\\.)?Color\\.(WHITE|BLACK|GRAY|LIGHT_GRAY|DARK_GRAY|RED|GREEN|BLUE|YELLOW|ORANGE"
                    + "|PINK|MAGENTA|CYAN)\\b");

    @Test
    void noColorLiteralsInTheApp() throws IOException {
        List<String> hits = new ArrayList<>();
        Path root = AppColorTokensTest.sources();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals("AppColorTokens.java")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    int comment = line.indexOf("//");
                    String code = comment >= 0 ? line.substring(0, comment) : line;
                    Matcher literal = LITERAL.matcher(code);
                    while (literal.find()) {
                        if (!transparent(literal.group())) {
                            hits.add(root.relativize(file) + ":" + (i + 1) + " " + literal.group());
                        }
                    }
                    Matcher constant = CONSTANT.matcher(code);
                    while (constant.find()) {
                        hits.add(root.relativize(file) + ":" + (i + 1) + " " + constant.group());
                    }
                }
            }
        }
        assertThat(hits).as("feste Farben (AppColors.of(\"app.Klasse.name\") benutzen)").isEmpty();
    }

    /** Vier Zahlen, die letzte (Alpha) ist 0. */
    private static boolean transparent(String literal) {
        String[] parts = literal.substring(literal.indexOf('(') + 1, literal.lastIndexOf(')')).split(",");
        return parts.length == 4 && Long.decode(parts[3].trim()) == 0;
    }
}
