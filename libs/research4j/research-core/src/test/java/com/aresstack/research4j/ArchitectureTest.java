package com.aresstack.research4j;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The research core is source-neutral: no browser, no search engine, no Ask AI namespace and no writing, neither in
 * its byte code nor on its class path.
 */
class ArchitectureTest {

    private static final List<String> FORBIDDEN = List.of("com/aresstack/askai", "com/microsoft/playwright",
            "playwright4j", "serp4j", "com/aresstack/writing4j", "javax/swing");

    @Test
    void theByteCodeReferencesNoBrowserSearchEngineOrWriting() throws IOException, URISyntaxException {
        Path classes = Path.of(com.aresstack.research4j.basket.BasketResearch.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        List<String> found = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                checked++;
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).toLowerCase();
                for (String forbidden : FORBIDDEN) {
                    if (bytes.contains(forbidden.toLowerCase())) {
                        found.add(classes.relativize(file) + " -> " + forbidden);
                    }
                }
            }
        }
        assertTrue(checked > 10, "the classes of research-core were checked");
        assertEquals(List.of(), found);
    }

    @Test
    void noBrowserIsOnTheClassPath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.microsoft.playwright.Playwright"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.aresstack.askai.browser.BrowserBackend"));
    }
}
