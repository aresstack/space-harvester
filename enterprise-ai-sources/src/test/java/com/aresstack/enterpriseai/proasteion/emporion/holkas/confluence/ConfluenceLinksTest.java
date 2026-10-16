package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Issue #94: links of a page view that point to a page of the same Confluence open in the reader. */
public class ConfluenceLinksTest {

    private static final String BASE = FakeConfluence.BASE.toString(); // https://confluence.example.org/wiki

    @Test
    public void pageAddressesUnderTheBaseNameTheirContentId() {
        assertEquals("123", page(BASE + "/pages/viewpage.action?pageId=123"));
        assertEquals("123", page(BASE + "/pages/viewpage.action?src=contextnavpagetreemode&pageId=123#Abschnitt"));
        assertEquals("456", page(BASE + "/spaces/DEV/pages/456/Some+Title"));
        assertEquals("456", page(BASE + "/spaces/DEV/pages/456"));
        assertEquals("1", page(BASE + "/x/AQ"));
        // Host case and the default port do not make an address foreign.
        assertEquals("123", page("https://CONFLUENCE.example.org:443/wiki/pages/viewpage.action?pageId=123"));
    }

    @Test
    public void addressPatternRecognizesWebAddressesAndLeavesOwnershipToTheResolver() {
        java.util.regex.Pattern pattern = ConfluenceLinks.addressPattern();

        assertTrue(pattern.matcher(BASE + "/pages/viewpage.action?pageId=123").matches());
        assertTrue(pattern.matcher("http://confluence.example.org/wiki/pages/viewpage.action?pageId=123").matches());
        assertTrue("anderer Host ist syntaktisch weiter eine Webadresse",
                pattern.matcher("https://example.org/other/page").matches());
        assertFalse(pattern.matcher("mailto:someone@example.org").matches());
        assertFalse(pattern.matcher("Text " + BASE + "/pages/viewpage.action?pageId=123").matches());

        assertNull("erst die gehintete Quelle prüft, ob die Webadresse wirklich ihr gehört",
                ConfluenceLinks.parse("https://example.org/other/page", BASE));
    }

    @Test
    public void tinyLinksDecodeTheLittleEndianContentId() {
        for (long id : new long[]{1L, 255L, 65536L, 123456789L, 2147483648L}) {
            assertEquals(Long.toString(id), ConfluenceLinks.tinyId(tiny(id)));
        }
    }

    @Test
    public void titleAndSpaceLinksNeedConfluenceToNameTheId() {
        ConfluenceLinks.Target titled = ConfluenceLinks.parse(BASE + "/display/DEV/Kapitel+1#Einleitung", BASE);
        assertNull(titled.pageId);
        assertEquals("DEV", titled.spaceKey);
        assertEquals("Kapitel 1", titled.title);
        ConfluenceLinks.Target query = ConfluenceLinks.parse(
                BASE + "/pages/viewpage.action?spaceKey=DEV&title=Kapitel%202", BASE);
        assertEquals("Kapitel 2", query.title);
        ConfluenceLinks.Target home = ConfluenceLinks.parse(BASE + "/display/DEV", BASE);
        assertEquals("DEV", home.spaceKey);
        assertNull(home.title);
    }

    @Test
    public void foreignAddressesAndOtherFormsAreNotPages() {
        for (String address : new String[]{
                "https://example.org/wiki/pages/viewpage.action?pageId=123", // other host
                "http://confluence.example.org/wiki/pages/viewpage.action?pageId=123", // other scheme
                "https://confluence.example.org:8443/wiki/pages/viewpage.action?pageId=123", // other port
                "https://confluence.example.org/other/pages/viewpage.action?pageId=123", // other context path
                "https://confluence.example.org/wikipedia/pages/viewpage.action?pageId=123", // prefix only
                "mailto:someone@example.org",
                "#Abschnitt",
                BASE + "/download/attachments/123/bild.png",
                BASE + "/display/DEV/2026/10/13/Blogbeitrag",
                BASE + "/pages/editpage.action?pageId=123",
                BASE + "/pages/viewpage.action?pageId=abc",
                BASE + "/dosearchsite.action?queryString=x"}) {
            assertNull(address, ConfluenceLinks.parse(address, BASE));
        }
    }

    @Test
    public void theSourceResolvesItsOwnLinksAndAsksConfluenceForTitles() throws Exception {
        FakeConfluence confluence = new FakeConfluence();
        confluence.page("100", "Start", "DEV", "<p>Willkommen</p>");
        confluence.page("101", "Kapitel 1", "DEV", "<p>Eins</p>");
        confluence.spaceHomepages.put("DEV", "100");
        ConfluenceKnowledgeSource source = new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF).build(),
                confluence, new RecordingSecretProvider());

        assertEquals(id("123"), source.resolveAddress(BASE + "/pages/viewpage.action?pageId=123"));
        assertTrue("eine ID braucht keine Anfrage", confluence.requests.isEmpty());
        assertEquals(id("101"), source.resolveAddress(BASE + "/display/DEV/Kapitel+1"));
        assertEquals(id("100"), source.resolveAddress(BASE + "/display/DEV/"));
        assertNull("unbekannter Titel: Browser", source.resolveAddress(BASE + "/display/DEV/Gibt+es+nicht"));
        assertNull("unbekannter Space: Browser", source.resolveAddress(BASE + "/display/NOPE"));
        List<URI> asked = new ArrayList<URI>(confluence.requests);
        confluence.requests.clear();
        assertNull(source.resolveAddress("https://example.org/wiki/display/DEV/Kapitel+1"));
        assertTrue("fremde Adressen fragen nie nach: " + asked, confluence.requests.isEmpty());
    }

    @Test
    public void aBookmarkLikeValueOfThisSourceIsReadAsItsWebAddress() throws Exception {
        FakeConfluence confluence = new FakeConfluence();
        ConfluenceKnowledgeSource source = new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF).build(),
                confluence, new RecordingSecretProvider());
        String bookmark = "confluence://confluence.example.org/wiki/spaces/DEV/pages/456/Some+Title";

        assertTrue(ConfluenceLinks.addressPattern().matcher(bookmark).matches());
        assertEquals(id("456"), source.resolveAddress(bookmark));
        assertNull("another Confluence keeps its bookmarks",
                source.resolveAddress("confluence://other.example.org/wiki/spaces/DEV/pages/456"));
        assertNull("prefix of a longer locator only",
                source.resolveAddress("confluence://confluence.example.org/wikipedia/spaces/DEV/pages/456"));
        assertEquals(BASE + "/spaces/DEV/pages/1", ConfluenceLinks.webAddressOf(
                "confluence://confluence.example.org/wiki/spaces/DEV/pages/1", "confluence.example.org/wiki", BASE));
        assertEquals("https://x.example.org/a", ConfluenceLinks.webAddressOf("https://x.example.org/a",
                "confluence.example.org/wiki", BASE));
    }

    @Test
    public void theViewPointsPageLinksAtTheirContentIdAndServerBaseLinksAtTheConfiguredBase() {
        String server = "http://wiki.invalid:8090/wiki";
        ConfluencePageView view = ConfluencePageView.of("<p>"
                + "<a href=\"/wiki/display/DEV/Kapitel+1#Einleitung\" data-linked-resource-id=\"101\" "
                + "data-linked-resource-type=\"page\">Kapitel 1</a> "
                + "<a href=\"" + server + "/spaces/DEV/pages/102\">Kapitel 2</a> "
                + "<a href=\"#lokal\">Anker</a> "
                + "<a href=\"https://example.org/x\">extern</a> "
                + "<a href=\"/wiki/download/attachments/1/a.pdf\" data-linked-resource-id=\"900\" "
                + "data-linked-resource-type=\"attachment\">Anhang</a></p>", BASE + "/", server);
        assertTrue(view.html, view.html.contains("href=\"" + BASE + "/pages/viewpage.action?pageId=101#Einleitung\""));
        assertTrue(view.html, view.html.contains("href=\"" + BASE + "/spaces/DEV/pages/102\""));
        assertTrue(view.html, view.html.contains("href=\"#lokal\""));
        assertTrue(view.html, view.html.contains("href=\"https://example.org/x\""));
        assertTrue(view.html, view.html.contains("href=\"" + BASE + "/download/attachments/1/a.pdf\""));
    }

    private static String page(String address) {
        ConfluenceLinks.Target target = ConfluenceLinks.parse(address, BASE);
        return target == null ? null : target.pageId;
    }

    private static KnowledgeResourceId id(String contentId) {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/" + contentId);
    }

    /** Confluence's encoding: eight little-endian bytes, Base64, '/' to '-', '+' to '_', without padding and zero 'A's. */
    private static String tiny(long id) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (id >>> (8 * i));
        }
        String encoded = java.util.Base64.getEncoder().encodeToString(bytes).replace('/', '-').replace('+', '_');
        int end = encoded.length();
        while (end > 0 && (encoded.charAt(end - 1) == '=' || encoded.charAt(end - 1) == 'A')) {
            end--;
        }
        return encoded.substring(0, end);
    }
}
