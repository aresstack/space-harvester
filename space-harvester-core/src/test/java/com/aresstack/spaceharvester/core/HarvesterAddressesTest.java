package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.PageBookmark;
import com.aresstack.spaceharvester.application.query.PageUrls;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adressauflösung (#178) gegen eine Fake-Confluence über echtes HTTP: Seitenadressen mit und ohne ID, Titel und
 * Startseite live, Bookmarks und Ressourcen-IDs der Quelle, Lesezeichen mit Version; Rechte am Ergebnis.
 */
class HarvesterAddressesTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.page("102", "Prüfung & Abnahme", "DEV", "<p>Nachtlauf</p>").storage("<p>Nachtlauf</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.homepage("DEV", "100");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void titlesHomePagesAndPageAddressesResolveLiveWithoutASync() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            assertThat(harvester.sources().sources().getFirst().capabilities()).contains(SourceCapability.ADDRESSES);

            assertThat(pageId(harvester, base() + "display/DEV/"
                    + URLEncoder.encode("Prüfung & Abnahme", StandardCharsets.UTF_8))).isEqualTo("102");
            assertThat(pageId(harvester, base() + "pages/viewpage.action?spaceKey=DEV&title=Start")).isEqualTo("100");
            assertThat(pageId(harvester, base() + "display/DEV")).isEqualTo("100");
            assertThat(pageId(harvester, base() + "pages/viewpage.action?pageId=102")).isEqualTo("102");
            assertThat(pageId(harvester, base() + "spaces/DEV/pages/102/Pruefung")).isEqualTo("102");
            assertThat(pageId(harvester, "  " + base() + "display/DEV/Start#Abschnitt  ")).isEqualTo("100");
        }
    }

    @Test
    void bookmarksAndResourceIdsOfTheSourceResolve() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            String resourceId = harvester.pages().page(harvester.caller(), "wiki", "102", false).header().metadata()
                    .resourceId();

            assertThat(resourceId).startsWith("confluence:");
            assertThat(pageId(harvester, resourceId)).isEqualTo("102");
            // Bookmark wie im Enterprise AI Client: Schema der Quelle mit //.
            assertThat(pageId(harvester, resourceId.replaceFirst(":", "://"))).isEqualTo("102");
            // Bookmark auf eine Seitenadresse unter dem Ort der Quelle.
            assertThat(pageId(harvester, resourceId.replaceFirst(":", "://").replace("/page/102",
                    "/spaces/DEV/pages/100"))).isEqualTo("100");
        }
    }

    @Test
    void bookmarksOfTheHarvesterAreStableIdsWithTheSeenVersion() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            PageAddresses.Resolution resolution = harvester.addresses().resolve(harvester.caller(),
                    base() + "display/DEV/Start");

            assertThat(resolution.matches()).extracting(PageAddresses.Match::sourceId, PageAddresses.Match::pageId,
                    PageAddresses.Match::spaceKey, PageAddresses.Match::title)
                    .containsExactly(tuple("wiki", "100", "DEV", "Start"));
            PageBookmark bookmark = resolution.matches().getFirst().bookmark();
            assertThat(bookmark.value()).isEqualTo("space-harvester://wiki/page/100?version="
                    + resolution.matches().getFirst().version());
            assertThat(PageBookmark.parse(bookmark.value())).contains(bookmark);
            assertThat(pageId(harvester, bookmark.value())).isEqualTo("100");
            assertThat(pageId(harvester, bookmark.withoutVersion().value())).isEqualTo("100");
            assertThat(harvester.addresses().resolve(harvester.caller(), bookmark.value()).matches())
                    .extracting(PageAddresses.Match::pageId).containsExactly("100");
            // Ein Lesezeichen einer anderen Quelle ist hier keine Seite.
            assertThatThrownBy(() -> harvester.pages().pageByUrl(harvester.caller(), "wiki",
                    "space-harvester://anders/page/100", false)).isInstanceOf(CacheMissException.class);
            assertThatThrownBy(() -> harvester.pages().pageByUrl(harvester.caller(), "wiki",
                    "space-harvester://wiki/seite/100", false)).isInstanceOf(InvalidQueryException.class);
        }
    }

    @Test
    void unknownForeignAndUnreadablePagesLookTheSame() {
        try (Harvester harvester = harvester(Harvester.builder().accessPolicy((context, scope, kind) ->
                "102".equals(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW))) {
            for (String address : new String[] {base() + "display/DEV/Unbekannt",
                    base() + "display/DEV/" + URLEncoder.encode("Prüfung & Abnahme", StandardCharsets.UTF_8),
                    "https://fremd.example.org/display/DEV/Start", "https://fremd.example.org/display/DEV",
                    "confluence://fremd.example.org/page/100"}) {
                assertThatThrownBy(() -> harvester.pages().pageByUrl(harvester.caller(), "wiki", address, false))
                        .as(address).isInstanceOfSatisfying(CacheMissException.class,
                                e -> assertThat(e.getMessage()).doesNotContain("102"));
            }
            assertThat(harvester.addresses().resolve(harvester.caller(), base() + "display/DEV/"
                    + URLEncoder.encode("Prüfung & Abnahme", StandardCharsets.UTF_8)).matches()).isEmpty();
            assertThatThrownBy(() -> harvester.pages().pageByUrl(harvester.caller(), "wiki", "Start", false))
                    .isInstanceOf(InvalidQueryException.class);
        }
    }

    @Test
    void theGlobalResolutionNeverFetchesAPageIdFromAForeignUrl() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            int before = CONFLUENCE.requests().size();

            assertThat(harvester.addresses().resolve(harvester.caller(),
                    "https://fremd.example.org/pages/viewpage.action?pageId=102").matches()).isEmpty();
            // Die Quelle ordnet die fremde Adresse nicht zu: kein Abruf der Seite 102.
            assertThat(CONFLUENCE.requests().subList(before, CONFLUENCE.requests().size()))
                    .noneMatch(uri -> uri.getPath().contains("102"));
        }
    }

    @Test
    void longPageIdsStayUsableAsBookmarks() {
        PageBookmark bookmark = new PageBookmark("docs", "a".repeat(PageUrls.MAX_URL_LENGTH), "7");

        assertThat(bookmark.value().length()).isGreaterThan(PageUrls.MAX_URL_LENGTH)
                .isLessThanOrEqualTo(PageBookmark.MAX_LENGTH);
        assertThat(PageBookmark.parse(bookmark.value())).contains(bookmark);
        assertThatThrownBy(() -> new PageBookmark("docs", "%".repeat(PageUrls.MAX_URL_LENGTH), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnreachableSourceIsNamedAndDoesNotHideTheOthers() {
        try (Harvester harvester = harvester(Harvester.builder().source(new SourceSettingsConfig("down",
                "confluence", "Weg", URI.create("http://127.0.0.1:1/"), SourceSettingsConfig.Auth.anonymous(),
                Map.of("connectTimeoutMillis", "500"))))) {
            PageAddresses.Resolution down = harvester.addresses().resolve(harvester.caller(),
                    "http://127.0.0.1:1/display/DEV/Start");
            PageAddresses.Resolution wiki = harvester.addresses().resolve(harvester.caller(),
                    base() + "display/DEV/Start");

            assertThat(down.matches()).isEmpty();
            assertThat(down.unavailableSources()).containsExactly("down");
            assertThat(wiki.matches()).extracting(PageAddresses.Match::sourceId).containsExactly("wiki");
            assertThat(wiki.unavailableSources()).isEmpty();
            assertThatThrownBy(() -> harvester.pages().pageByUrl(harvester.caller(), "down",
                    "http://127.0.0.1:1/display/DEV/Start", false)).isInstanceOf(SourceAccessException.class);
        }
    }

    private static String pageId(Harvester harvester, String address) {
        return harvester.pages().pageByUrl(harvester.caller(), "wiki", address, false).header().key().pageId();
    }

    /** Basis-URL der Fake-Confluence mit abschließendem Schrägstrich. */
    private static URI base() {
        String base = SERVER.baseUrl().toString();
        return URI.create(base.endsWith("/") ? base : base + "/");
    }

    private Harvester harvester(Harvester.Builder builder) {
        return builder
                .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                .dataDirectory(data)
                .build();
    }
}
