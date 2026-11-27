package com.aresstack.askai.browser.search.analysis;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The tiny JSON reader parses the stable schema and rejects malformed input rather than guessing. */
public class MiniJsonTest {

    @Test
    @SuppressWarnings("unchecked")
    public void parsesObjectsArraysStringsNumbersAndBooleans() {
        Object root = MiniJson.parse("{\"a\":\"x\",\"b\":[1,2.5],\"c\":true,\"d\":null}");
        Map<String, Object> object = (Map<String, Object>) root;
        assertEquals("x", object.get("a"));
        assertEquals(2, ((List<Object>) object.get("b")).size());
        assertEquals(Boolean.TRUE, object.get("c"));
        assertTrue(object.containsKey("d"));
        assertEquals(null, object.get("d"));
    }

    @Test
    public void stripsAFencedCodeBlock() {
        Object root = MiniJson.parse("```json\n{\"k\":\"v\"}\n```");
        assertEquals("v", ((Map<?, ?>) root).get("k"));
    }

    @Test
    public void rejectsMalformedJson() {
        for (String bad : new String[]{"", "   ", "{", "{\"a\":}", "[1,2", "nope",
                "{\"a\":1} trailing"}) {
            try {
                MiniJson.parse(bad);
                fail("expected parse failure for: '" + bad + "'");
            } catch (MiniJson.JsonParseException expected) {
                // expected
            }
        }
    }
}
