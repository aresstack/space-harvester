package com.aresstack.spaceharvester.provingground.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckTest {

    private static boolean test(String type, String expected, String actual) {
        Check check = new Check(type, expected);
        return check.test(actual, check.expected());
    }

    @Test
    void validatorTypesAsInWd4j() {
        assertTrue(test("regex", "OK-[0-9]+", "Status OK-17"));
        assertTrue(test("regex", "", "anything"));
        assertTrue(test("fullregex", "^OK-[0-9]+$", " OK-17 "), "values are trimmed");
        assertFalse(test("fullregex", "OK", "OK-17"));
        assertTrue(test("fullregex", "", ""));
        assertTrue(test("contains", "Gespeichert", "Vorgang Gespeichert."));
        assertTrue(test("equals", "Bea", "Bea"));
        assertFalse(test("equals", "Bea", "bea"));
        assertTrue(test("starts", "Vor", "Vorgang"));
        assertTrue(test("ends", "gang", "Vorgang"));
        assertTrue(test("range", "-5.5:10.75", "10.75"));
        assertFalse(test("range", "0:1", "1.1"));
        assertFalse(test("range", "0:1", "kein"));
        assertTrue(test("len", "3", "abc"));
        assertTrue(test("len", ">=2", "abc"));
        assertFalse(test("len", "<=2", "abc"));
        assertFalse(test("unknown", "x", "x"));
        assertFalse(test("regex", "([", "x"), "an invalid pattern fails instead of throwing");
    }

    @Test
    void negationAndAlternatives() {
        assertTrue(test("!contains", "Fehler", "Gespeichert"));
        assertFalse(test("!contains", "Fehler", "Fehler 17"));
        assertTrue(test("equals", "Gespeichert||Übernommen", "Übernommen"));
        assertFalse(test("!equals", "Gespeichert||Übernommen", "Übernommen"));
    }
}
