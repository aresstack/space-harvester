package com.aresstack.spaceharvester.provingground.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCaseEditTest {

    @Test
    void stepsAreEditedMovedInsertedAndRemoved() {
        TestCase test = new TestCase("t");
        test.add(TestStep.navigate("p", "http://example.invalid/a"));
        test.add(TestStep.assertVisible("p", "internal:role=button[name=\"Ok\"i]"));
        test.replace(0, test.step(0).withUrl("http://example.invalid/b"));
        test.insert(1, TestStep.assertText("p", "h1", "Titel", true));
        test.move(2, 0);
        assertEquals("assertVisible", test.step(0).name());
        assertEquals("http://example.invalid/b", test.step(1).url());
        assertTrue(test.step(2).isAssertion());
        assertEquals("assertText", test.remove(2).name());
        assertEquals(2, test.size());
    }
}
