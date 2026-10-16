package com.aresstack.enterpriseai.astu.security;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SecretRefTest {

    @Test
    public void trimsAndExposesTheId() {
        assertEquals("keepass:confluence", SecretRef.of("  keepass:confluence ").id());
    }

    @Test
    public void isAValueObject() {
        assertEquals(SecretRef.of("a"), SecretRef.of("a"));
        assertEquals(SecretRef.of("a").hashCode(), SecretRef.of(" a").hashCode());
        assertNotEquals(SecretRef.of("a"), SecretRef.of("b"));
    }

    @Test
    public void toStringIsLoggable() {
        assertEquals("SecretRef[keepass:wiki]", SecretRef.of("keepass:wiki").toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNull() {
        SecretRef.of(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlank() {
        SecretRef.of("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsControlCharactersSoLogLinesCannotBeForged() {
        SecretRef.of("wiki\nFAKE LOG LINE");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsControlCharactersAtTheEnd() {
        SecretRef.of("wiki\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLeadingTab() {
        SecretRef.of("\twiki");
    }
}
