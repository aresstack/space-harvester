package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TypedBlacklistTest {

    @Test
    public void aTermMatchesAsAWholeWordInTitleSnippetOrUrl() {
        TypedBlacklist list = TypedBlacklist.of(Arrays.asList("Java"));
        assertEquals("Java", list.firstMatch("Learn JAVA today", "", "https://a.org/x"));
        assertEquals("Java", list.firstMatch("", "all about java.", ""));
        assertEquals("Java", list.firstMatch("", "", "https://a.org/java/intro"));
        assertNull("no partial words", list.firstMatch("JavaScript basics", "javascripting", "https://js.org"));
    }

    @Test
    public void aHostEntryBlocksTheDomainAndItsSubdomainsOnly() {
        TypedBlacklist list = TypedBlacklist.of(Arrays.asList("https://www.Example.org/path"));
        assertTrue(TypedBlacklist.isHostEntry("example.org"));
        assertFalse(TypedBlacklist.isHostEntry("example org"));
        assertTrue(list.matches("", "", "https://example.org/a"));
        assertTrue(list.matches("", "", "http://docs.example.org/a"));
        assertFalse(list.matches("example.org is mentioned", "example.org", "https://other.org/"));
        assertFalse(list.matches("", "", "https://notexample.org/"));
        assertTrue("query right after the authority", list.matches("", "", "https://example.org?lang=en"));
        assertTrue("fragment right after the authority", list.matches("", "", "https://example.org#top"));
        assertTrue("user info is not the host", list.matches("", "", "https://u@example.org:8080/x"));
    }

    @Test
    public void blankAndDuplicateEntriesAreDropped() {
        TypedBlacklist list = TypedBlacklist.of(Arrays.asList(" a ", "", null, "A", "b c"));
        assertEquals(Arrays.asList("a", "b c"), list.entries());
        assertTrue(TypedBlacklist.of(Arrays.<String>asList()).isEmpty());
    }

    @Test
    public void aPhraseMatchesAsAWhole() {
        TypedBlacklist list = TypedBlacklist.of(Arrays.asList("garbage collection"));
        assertTrue(list.matches("Tuning Garbage Collection", "", ""));
        assertFalse(list.matches("garbage and collection", "", ""));
    }
}
