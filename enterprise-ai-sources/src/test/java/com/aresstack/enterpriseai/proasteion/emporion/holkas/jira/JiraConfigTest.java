package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JiraConfigTest {

    @Test
    public void normalizesTheBaseUrlAndHasDefaults() {
        JiraConfig config = JiraConfig.builder(URI.create("https://host/jira//")).build();
        assertEquals(URI.create("https://host/jira"), config.baseUrl());
        assertNull(config.credentialRef());
        assertEquals(50, config.pageSize());
        assertEquals(200, config.maxComments());
        assertEquals("", config.jqlFilter());
        assertEquals(0, config.maxRetries());
    }

    @Test
    public void rejectsUnsafeBaseUrlsAndOrderByInTheFilter() {
        for (String url : new String[] {"ftp://host", "https://user:pw@host", "https://host/?q=1", "https://host/#x"}) {
            try {
                JiraConfig.builder(URI.create(url));
                fail(url);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage(), expected.getMessage().contains("pw"));
            }
        }
        try {
            JiraConfig.builder(URI.create("https://host")).jqlFilter("project = A order by key");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ORDER BY"));
        }
    }

    @Test
    public void credentialsOverPlainHttpNeedAnExplicitOptIn() {
        try {
            JiraConfig.builder(URI.create("http://host")).credentialRef(SecretRef.of("keepass:j")).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("https"));
        }
        assertTrue(JiraConfig.builder(URI.create("http://host")).credentialRef(SecretRef.of("keepass:j"))
                .allowInsecureHttp(true).build().allowInsecureHttp());
    }

    @Test
    public void toStringShowsTheRefButNoSecret() {
        String text = JiraConfig.builder(URI.create("https://host")).credentialRef(SecretRef.of("keepass:jira"))
                .jqlFilter("resolution = Unresolved").build().toString();
        assertTrue(text, text.contains("SecretRef[keepass:jira]"));
        assertTrue(text, text.contains("resolution = Unresolved"));
    }
}
