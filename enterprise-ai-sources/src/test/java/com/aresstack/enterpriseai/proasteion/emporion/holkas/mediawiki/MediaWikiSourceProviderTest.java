package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourceScope;
import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Transport-Naht des Providers: eigener Transport je Quelle, sonst der Standardtransport. */
public class MediaWikiSourceProviderTest {

    private static final SourceAddress SOURCE = SourceAddress.of("wiki-intranet");

    private final MediaWikiSourceProvider provider =
            new MediaWikiSourceProvider(ref -> MediaWikiCredentialsProvider.anonymous(), null, null, "Test");

    @Test
    public void openUsesTheTransportOfTheFactoryWithTheSiteFromTheSettings() throws Exception {
        final FakeMediaWikiTransport wiki = MediaWikiKnowledgeSourceContractTest.sampleWiki();
        final List<MediaWikiSiteConfig> sites = new ArrayList<MediaWikiSiteConfig>();
        KnowledgeSourcePort source = provider.withTransports((sourceId, site) -> {
            assertEquals(SOURCE, sourceId);
            sites.add(site);
            return wiki;
        }).open(SOURCE, settings("https://wiki.example/w/api.php"));

        List<KnowledgeResource> resources = source.discover(SourceScope.builder().startPoint("Hauptseite").build());

        assertEquals("wiki:wiki.example/w/Hauptseite", resources.get(0).id().value());
        assertFalse(wiki.requests.isEmpty());
        assertEquals(1, sites.size());
        assertEquals("https://wiki.example/w/api.php", sites.get(0).apiEndpoint());
        assertEquals(1234, sites.get(0).readTimeoutMillis());
        assertEquals("Test", sites.get(0).userAgent());
    }

    @Test
    public void aFactoryWithoutTransportKeepsTheStandardTransport() throws Exception {
        try (FakeMediaWikiServer server = new FakeMediaWikiServer(MediaWikiKnowledgeSourceContractTest.sampleWiki())) {
            KnowledgeSourcePort source = provider.withTransports((sourceId, site) -> null)
                    .open(SOURCE, settings(server.apiUrl() + "/api.php"));

            List<KnowledgeResource> resources = source.discover(SourceScope.builder().startPoint("Hauptseite").build());

            assertEquals("Hauptseite", resources.get(0).title());
            assertFalse(server.wiki().requests.isEmpty());
        }
    }

    @Test
    public void withTransportsRejectsNull() {
        try {
            provider.withTransports(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // erwartet
        }
    }

    @Test
    public void theSeamIsPublicForOtherPackages() throws Exception {
        assertTrue(Modifier.isPublic(MediaWikiTransport.class.getModifiers()));
        assertTrue(Modifier.isPublic(MediaWikiTransport.Response.class.getConstructor(int.class, String.class)
                .getModifiers()));
        assertTrue(Modifier.isPublic(MediaWikiTransport.Response.class.getMethod("status").getModifiers()));
        assertTrue(Modifier.isPublic(MediaWikiTransport.Response.class.getMethod("body").getModifiers()));
        assertTrue(Modifier.isPublic(MediaWikiKnowledgeSource.class.getConstructor(SourceAddress.class,
                MediaWikiSiteConfig.class, MediaWikiTransport.class, MediaWikiCredentialsProvider.class)
                .getModifiers()));
        assertEquals("", new MediaWikiTransport.Response(204, null).body());
    }

    private static SourceSettings settings(String apiUrl) {
        Map<String, String> values = new HashMap<String, String>();
        values.put("apiUrl", apiUrl);
        values.put("startPoints", "Hauptseite");
        values.put("readTimeoutMillis", "1234");
        return SourceSettings.of(values);
    }
}
