package com.aresstack.spaceharvester.provingground.store;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCaseFileTest {

    @TempDir
    Path temp;

    private static TestStep recorded(String json) {
        return TestStep.of(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void stepsSurviveWritingAndReadingWithEveryPlaywrightField() throws IOException {
        TestCase test = new TestCase("Anmelden");
        test.add(recorded("{\"pageGuid\":\"page@1\",\"action\":{\"name\":\"openPage\",\"url\":\"about:blank\","
                + "\"signals\":[]},\"startTime\":1}"));
        test.add(recorded("{\"pageGuid\":\"page@1\",\"action\":{\"name\":\"click\",\"selector\":"
                + "\"internal:role=button[name=\\\"Speichern\\\"i]\",\"ref\":\"e6\",\"ariaSnapshot\":\"- button\","
                + "\"signals\":[],\"button\":\"left\",\"modifiers\":0,\"clickCount\":1},\"startTime\":2}"));
        test.add(TestStep.assertText("page@1", "internal:text=\"Gespeichert\"", "Gespeichert", false));
        Path file = temp.resolve("anmelden.jsonl");

        TestCaseFile.write(test, file);
        TestCase read = TestCaseFile.read(file);

        assertEquals("Anmelden", read.name());
        assertEquals(3, read.size());
        assertEquals("internal:role=button[name=\"Speichern\"i]", read.step(1).selector());
        assertEquals("e6", read.step(1).actionJson().get("ref").getAsString(), "unknown fields are kept");
        assertFalse(read.step(1).actionJson().has("ariaSnapshot"), "recording-time snapshot not stored");
        assertEquals("Gespeichert", read.step(2).text());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(4, lines.size(), "header + one line per action");
        assertTrue(lines.get(0).contains("\"provingGround\":1"));
    }

    @Test
    void theSnapshotOfASnapshotAssertionIsKept() throws IOException {
        TestCase test = new TestCase("t");
        test.add(recorded("{\"pageGuid\":\"p\",\"action\":{\"name\":\"assertSnapshot\",\"selector\":\"body\","
                + "\"ariaSnapshot\":\"- heading \\\"Hallo\\\"\",\"signals\":[]}}"));
        Path file = temp.resolve("s.jsonl");
        TestCaseFile.write(test, file);
        assertEquals("- heading \"Hallo\"",
                TestCaseFile.read(file).step(0).actionJson().get("ariaSnapshot").getAsString());
    }

    @Test
    void aFileWithoutHeaderIsRejected() throws IOException {
        Path file = temp.resolve("x.jsonl");
        Files.writeString(file, "{\"pageGuid\":\"p\",\"action\":{\"name\":\"click\"}}\n");
        assertThrows(IOException.class, () -> TestCaseFile.read(file));
    }
}
