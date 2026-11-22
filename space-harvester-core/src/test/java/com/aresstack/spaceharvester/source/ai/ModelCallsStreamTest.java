package com.aresstack.spaceharvester.source.ai;

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
 * Angelo 22.11.2026: Jeder Aufruf eines Chat-Modells streamt. Ohne Streaming fließen bei langen Antworten bis zum
 * Schluss keine Bytes, und ein Proxy oder Gateway bricht ab (HTTP 504). Dieser Test wird rot, sobald irgendwo im
 * Produktivcode von Space Harvester ein Chat-Modell ohne Streaming gefragt wird ({@code ….complete(ChatRequest)} statt
 * {@link RemoteAi#streamed}, oder {@code "stream": false} an {@code /api/chat}). Die Upstream-Kopie des Enterprise AI
 * Client ist ausgenommen; sie bietet beide Wege an.
 */
class ModelCallsStreamTest {

    /** {@code port.complete(new ChatRequest(…))} oder {@code port.complete(request)}. */
    private static final Pattern BLOCKING_CALL = Pattern.compile("\\.complete\\(\\s*(new\\s+ChatRequest\\b|request\\s*\\))");
    /** {@code "stream", false} bzw. {@code "stream": false} in einem Anfragekörper. */
    private static final Pattern NOT_STREAMING = Pattern.compile("\"stream\"\\s*[,:]\\s*false");
    /** Laden eines Ollama-Modells mit leerem Prompt ist keine Modellantwort und darf blockieren. */
    private static final String ALLOWED_LOAD = "OllamaModelCatalog.java";

    @Test
    void everyChatModelCallStreams() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<String> found = new ArrayList<>();
        List<Path> modules;
        try (Stream<Path> children = Files.list(root)) {
            modules = children.filter(dir -> dir.getFileName().toString().startsWith("space-harvester"))
                    .map(dir -> dir.resolve("src/main/java")).filter(Files::isDirectory).toList();
        }
        assertThat(modules).as("Produktivquellen gefunden").isNotEmpty();
        for (Path module : modules) {
            try (Stream<Path> files = Files.walk(module)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher blocking = BLOCKING_CALL.matcher(text);
                    while (blocking.find()) {
                        found.add(root.relativize(file) + ": " + blocking.group());
                    }
                    if (!file.getFileName().toString().equals(ALLOWED_LOAD)) {
                        Matcher flag = NOT_STREAMING.matcher(text);
                        while (flag.find()) {
                            found.add(root.relativize(file) + ": " + flag.group());
                        }
                    }
                }
            }
        }
        assertThat(found).as("Modellaufrufe ohne Streaming").isEmpty();
    }
}
