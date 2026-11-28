package com.aresstack.spaceharvester.app.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.ui.comic.theme.AskAiTheme;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Die App-Token: jede benutzte Stelle hat ihren Eintrag, jeder Eintrag hat eine Stelle, die Farben stehen im Theme. */
class AppColorTokensTest {

    private static final Pattern USE = Pattern.compile("AppColors\\.of\\(\"(app\\.[A-Za-z0-9_.]+)\"\\)");

    static Path sources() {
        for (String candidate : new String[] {"src/main/java", "space-harvester-app/src/main/java"}) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new IllegalStateException("Quellordner der App nicht gefunden");
    }

    static Set<String> usedKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = USE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }

    @Test
    void everyUsedKeyHasATableEntryAndEveryEntryIsUsed() throws IOException {
        Set<String> table = new TreeSet<>(AppColorTokens.classic().keySet());
        Set<String> used = usedKeys();
        List<String> missing = new ArrayList<>(used);
        missing.removeAll(table);
        List<String> unused = new ArrayList<>(table);
        unused.removeAll(used);
        assertThat(missing).as("benutzt, aber ohne Eintrag").isEmpty();
        assertThat(unused).as("Eintrag ohne Stelle").isEmpty();
    }

    @Test
    void theClassicThemeShowsTheTableValuesThroughLiveColors() {
        for (Map.Entry<String, Color> entry : AppColorTokens.classic().entrySet()) {
            assertThat(AppColors.of(entry.getKey()).getRGB()).as(entry.getKey()).isEqualTo(entry.getValue().getRGB());
            assertThat(AskAiTheme.classic().color(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    private static final Pattern METHOD_NAME = Pattern.compile(
            "(paint|init|update|render|actionPerformed|componentResized|getIcon|getScrollable|windowClosing|enter|apply|"
                    + "parseViewBox|slideIndex)[A-Za-z0-9]*|.*[0-9]");

    @Test
    void tokenNamesSayTheRoleNeverTheMethodOrARunningNumber() {
        List<String> offenders = new ArrayList<>();
        for (String key : AppColorTokens.classic().keySet()) {
            String name = key.substring(key.lastIndexOf('.') + 1);
            if (METHOD_NAME.matcher(name).matches()) {
                offenders.add(key);
            }
        }
        assertThat(offenders).as("Namen nach Methode oder mit Laufnummer, bitte nach Rolle benennen").isEmpty();
    }

    @Test
    void anUnknownKeyIsRejectedImmediately() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AppColors.of("app.Gibt.es.nicht"));
    }
}
