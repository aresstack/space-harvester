package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfluenceConfigTest {

    private static final String SOURCE = "dc";

    @Test
    public void normalizesTheBaseUrlAndHasDefaults() {
        ConfluenceConfig config = ConfluenceConfig.builder(URI.create("https://host/confluence//")).build();
        assertEquals(URI.create("https://host/confluence"), config.baseUrl());
        assertNull(config.credentialRef());
        assertEquals(50, config.pageSize());
        assertFalse(config.includeAttachments());
        assertTrue(config.searchSpaceKeys().isEmpty());
    }

    @Test
    public void rejectsUnsafeBaseUrls() {
        for (String url : new String[] {"ftp://host", "https://user:pw@host", "https://host/?q=1", "https://host/#x",
                "file:///etc"}) {
            try {
                ConfluenceConfig.builder(URI.create(url));
                fail(url);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage(), expected.getMessage().contains("pw"));
            }
        }
    }

    @Test
    public void credentialsOverPlainHttpNeedAnExplicitOptIn() {
        try {
            ConfluenceConfig.builder(URI.create("http://host")).credentialRef(SecretRef.of("keepass:c")).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("https"));
        }
        assertTrue(ConfluenceConfig.builder(URI.create("http://host")).credentialRef(SecretRef.of("keepass:c"))
                .allowInsecureHttp(true).build().allowInsecureHttp());
        assertNull(ConfluenceConfig.builder(URI.create("http://host")).build().credentialRef());
    }

    @Test
    public void toStringShowsTheRefButNoSecret() {
        String text = ConfluenceConfig.builder(URI.create("https://host"))
                .credentialRef(SecretRef.of("keepass:confluence")).build().toString();
        assertTrue(text, text.contains("SecretRef[keepass:confluence]"));
    }

    @Test
    public void startPointForms() {
        assertEquals("123", ConfluenceIds.startPoint(SOURCE, " 123 ").pageId);
        assertEquals("123", ConfluenceIds.startPoint(SOURCE, "PAGE:123").pageId);
        assertEquals("DEV", ConfluenceIds.startPoint(SOURCE, "space:DEV").spaceKey);
        assertEquals("~alice", ConfluenceIds.startPoint(SOURCE, "~alice").spaceKey);
        assertEquals("DEV", ConfluenceIds.startPoint(SOURCE, "DEV").spaceKey);
        assertEquals("7", ConfluenceIds.startPoint(SOURCE, "confluence:dc/page/7").pageId);
        assertNull(ConfluenceIds.startPoint(SOURCE, "confluence:dc/attachment/att7"));
        assertNull(ConfluenceIds.startPoint(SOURCE, "confluence:other/page/7"));
        assertNull(ConfluenceIds.startPoint(SOURCE, "page:abc"));
        assertNull(ConfluenceIds.startPoint(SOURCE, "space:a b"));
        assertNull(ConfluenceIds.startPoint(SOURCE, "a/../b"));
    }

    @Test
    public void idsRoundTrip() {
        KnowledgeResourceId page = ConfluenceIds.page(SOURCE, "42");
        assertEquals("confluence:dc/page/42", page.value());
        assertEquals("42", ConfluenceIds.parse(SOURCE, page).contentId);
        assertTrue(ConfluenceIds.parse(SOURCE, ConfluenceIds.attachment(SOURCE, "att42")).attachment);
        assertNull(ConfluenceIds.parse("other", page));
        assertNull(ConfluenceIds.parse(SOURCE, KnowledgeResourceId.of("confluence", "dc/page/42/x")));
    }
}
