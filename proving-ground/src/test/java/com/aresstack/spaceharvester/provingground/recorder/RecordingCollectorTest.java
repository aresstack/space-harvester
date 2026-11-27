package com.aresstack.spaceharvester.provingground.recorder;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The recorder's events as Playwright 1.62 sends them (shape taken from a live recording). */
class RecordingCollectorTest {

    private static JsonObject event(String kind, String data) {
        return JsonParser.parseString("{\"event\":\"" + kind + "\",\"data\":" + data + ",\"code\":\"\"}")
                .getAsJsonObject();
    }

    private static String fill(String field, String text) {
        return "{\"pageGuid\":\"page@1\",\"action\":{\"name\":\"fill\",\"selector\":\"internal:role=textbox[name=\\\""
                + field + "\\\"i]\",\"signals\":[],\"text\":\"" + text + "\"},\"startTime\":1}";
    }

    @Test
    void typingIntoTwoFieldsInARowKeepsBoth() {
        TestCase test = new TestCase("t");
        RecordingCollector collector = new RecordingCollector(test, true);
        collector.apply(event("actionAdded", fill("Name", "A")), step -> false);
        collector.apply(event("actionUpdated", fill("Name", "Angelo")), step -> false);
        collector.apply(event("actionAdded", fill("Ort", "B")), step -> false);
        collector.apply(event("actionUpdated", fill("Ort", "Bonn")), step -> false);
        assertEquals(2, test.size());
        assertEquals("Angelo", test.step(0).text());
        assertEquals("Bonn", test.step(1).text());
    }

    @Test
    void aPasswordNeverReachesTheTestCase() {
        TestCase test = new TestCase("t");
        RecordingCollector collector = new RecordingCollector(test, true);
        collector.apply(event("actionAdded", fill("Passwort", "g")), step -> true);
        collector.apply(event("actionUpdated", fill("Passwort", "geheim")), step -> {
            throw new AssertionError("asked only for a new fill");
        });
        assertEquals(1, test.size());
        assertEquals("${secret.Passwort}", test.step(0).text());
    }

    @Test
    void withoutMaskingTheTextStays() {
        TestCase test = new TestCase("t");
        new RecordingCollector(test, false).apply(event("actionAdded", fill("Passwort", "geheim")), step -> true);
        assertEquals("geheim", test.step(0).text());
    }

    @Test
    void aSignalGoesToTheLastStepOfItsPage() {
        TestCase test = new TestCase("t");
        RecordingCollector collector = new RecordingCollector(test, true);
        collector.apply(event("actionAdded", "{\"pageGuid\":\"page@1\",\"action\":{\"name\":\"click\","
                + "\"selector\":\"internal:role=link[name=\\\"Hilfe\\\"i]\",\"signals\":[]}}"), step -> false);
        collector.apply(event("signalAdded", "{\"pageGuid\":\"page@1\",\"signal\":{\"name\":\"popup\","
                + "\"popupPageGuid\":\"page@2\"},\"timestamp\":5}"), step -> false);
        assertNotNull(test.step(0).signal("popup"));
        assertEquals("page@2", test.step(0).signal("popup").get("popupPageGuid").getAsString());
    }

    @Test
    void fieldNamesComeFromTheLocator() {
        assertEquals("Passwort", RecordingCollector.fieldName("internal:role=textbox[name=\"Passwort\"i]"));
        assertEquals("Kennwort_neu", RecordingCollector.fieldName("internal:label=\"Kennwort (neu)\"i"));
        assertEquals("password", RecordingCollector.fieldName("#pw"));
        assertEquals("password", RecordingCollector.fieldName(null));
    }
}
