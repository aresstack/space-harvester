package com.aresstack.spaceharvester.provingground.store;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A test case as a JSONL file, laid out like Playwright's own JSONL recording ({@code JsonlLanguageGenerator}): the
 * first line is a header, every further line one action. An action line is Playwright's {@code ActionInContext}
 * exactly as the recorder reported it, without the page's ARIA snapshot at the time of recording (Playwright's JSONL
 * drops it as well; {@code assertSnapshot} keeps it, there it is the expected value). No conversion into an own action
 * model, and nothing stored twice.
 *
 * <pre>
 * {"provingGround":1,"name":"Anmelden","playwright":"1.62.0"}
 * {"pageGuid":"page@1","action":{"name":"openPage","url":"about:blank","signals":[]},"startTime":...}
 * {"pageGuid":"page@1","action":{"name":"fill","selector":"internal:label=\"Name\"i","text":"Angelo","signals":[]}}
 * </pre>
 */
public final class TestCaseFile {

    /** Version of the header layout. */
    public static final int FORMAT = 1;
    /** The Playwright version whose action format the file holds. */
    public static final String PLAYWRIGHT_VERSION = "1.62.0";
    public static final String EXTENSION = ".jsonl";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private TestCaseFile() {
    }

    /** Writes {@code testCase} to {@code file} (whole file, replaced atomically where the file system allows). */
    public static void write(TestCase testCase, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                JsonObject header = new JsonObject();
                header.addProperty("provingGround", FORMAT);
                header.addProperty("name", testCase.name());
                header.addProperty("playwright", PLAYWRIGHT_VERSION);
                out.write(GSON.toJson(header));
                out.write('\n');
                for (TestStep step : testCase.steps()) {
                    out.write(GSON.toJson(stored(step)));
                    out.write('\n');
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException noAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Reads a test case written by {@link #write}. */
    public static TestCase read(Path file) throws IOException {
        List<TestStep> steps = new ArrayList<>();
        String name = null;
        int lineNumber = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                JsonObject json;
                try {
                    json = JsonParser.parseString(line).getAsJsonObject();
                } catch (JsonParseException | IllegalStateException broken) {
                    throw new IOException(file + ", line " + lineNumber + ": no JSON object", broken);
                }
                if (name == null) {
                    if (!json.has("provingGround")) {
                        throw new IOException(file + ": no Proving Ground header in line " + lineNumber);
                    }
                    int format = json.get("provingGround").getAsInt();
                    if (format > FORMAT) {
                        throw new IOException(file + ": written by a newer Proving Ground (format " + format + ")");
                    }
                    name = json.has("name") ? json.get("name").getAsString() : fileName(file);
                    continue;
                }
                try {
                    steps.add(TestStep.of(json));
                } catch (IllegalArgumentException broken) {
                    throw new IOException(file + ", line " + lineNumber + ": " + broken.getMessage(), broken);
                }
            }
        }
        if (name == null) {
            throw new IOException(file + ": empty");
        }
        return new TestCase(name, steps);
    }

    /** What goes into the file: the step without the recording-time ARIA snapshot (kept for assertSnapshot). */
    static JsonObject stored(TestStep step) {
        JsonObject json = step.toJson();
        if (!"assertSnapshot".equals(step.name())) {
            json.getAsJsonObject("action").remove("ariaSnapshot");
        }
        return json;
    }

    private static String fileName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(EXTENSION) ? name.substring(0, name.length() - EXTENSION.length()) : name;
    }
}
