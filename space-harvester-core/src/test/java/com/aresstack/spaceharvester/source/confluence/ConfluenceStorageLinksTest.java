package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.links.LinkTarget;
import org.junit.jupiter.api.Test;

/** Verweisziele aus dem Storage-Format (Paket V1). */
class ConfluenceStorageLinksTest {

    private static final String LOCATOR = "cf.intern/confluence";

    @Test
    void readsPageLinksTitlesIdsAndAddressesOfThisConfluenceOnly() {
        String storage = "<p><ac:link><ri:page ri:content-title=\"Plan &amp; Ziel\"/></ac:link>"
                + "<ac:link><ri:page ri:space-key=\"OPS\" ri:content-title=\"Betrieb\"/></ac:link>"
                + "<a href=\"/confluence/spaces/DEV/pages/42/Titel\">a</a>"
                + "<a href=\"https://CF.intern:443/confluence/pages/viewpage.action?spaceKey=DEV&amp;title=Neu+Seite\">b</a>"
                + "<a href=\"https://cf.intern/confluence/display/DEV/Anleitung#abschnitt\">c</a>"
                + "<a href=\"https://cf.intern/andere/display/DEV/Fremd\">d</a>"
                + "<a href=\"https://extern.example.org/x\">e</a>"
                + "<a href=\"https://cf.intern/confluence/display/DEV\">Startseite</a></p>";

        assertThat(ConfluenceStorageLinks.of(storage, LOCATOR, "1", "DEV", "Start")).containsExactly(
                LinkTarget.titled("DEV", "Plan & Ziel"), LinkTarget.titled("OPS", "Betrieb"), LinkTarget.page("42"),
                LinkTarget.titled("DEV", "Neu Seite"), LinkTarget.titled("DEV", "Anleitung"));
    }

    @Test
    void leavesOutAttachmentsMacroParametersImagesCodeSelfAndDuplicates() {
        String storage = "<ac:link><ri:attachment ri:filename=\"a.pdf\"><ri:page ri:content-title=\"Andere\"/>"
                + "</ri:attachment></ac:link>"
                + "<ac:structured-macro ac:name=\"include\"><ac:parameter ac:name=\"\">"
                + "<ac:link><ri:page ri:content-title=\"Eingebettet\"/></ac:link></ac:parameter></ac:structured-macro>"
                + "<ac:image><ri:url ri:value=\"https://cf.intern/confluence/pages/viewpage.action?pageId=9\"/>"
                + "</ac:image>"
                + "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body><![CDATA["
                + "<a href=\"/confluence/pages/viewpage.action?pageId=8\">x</a>]]></ac:plain-text-body>"
                + "</ac:structured-macro>"
                + "<!-- <a href=\"/confluence/pages/viewpage.action?pageId=7\">x</a> -->"
                + "<ac:link><ri:page ri:content-title=\"Start\"/></ac:link>"
                + "<a href=\"/confluence/pages/viewpage.action?pageId=1\">selbst</a>"
                + "<ac:link><ri:page ri:content-title=\"Ziel\"/></ac:link>"
                + "<ac:link><ri:page ri:content-title=\"ziel\"/></ac:link>"
                + "<ac:link><ri:blog-post ri:content-title=\"Blog\" ri:posting-day=\"2026/11/01\"/></ac:link>"
                + "<ac:link><ri:user ri:userkey=\"abc\"/></ac:link>";

        assertThat(ConfluenceStorageLinks.of(storage, LOCATOR, "1", "DEV", "Start"))
                .containsExactly(LinkTarget.titled("DEV", "Ziel"));
    }

    @Test
    void decodesShortLinksLikeTheConnector() {
        assertThat(ConfluenceStorageLinks.href("/confluence/x/AQAAAA", LOCATOR))
                .isEqualTo(LinkTarget.page(ConfluenceStorageLinks.tinyId("AQAAAA")));
        assertThat(ConfluenceStorageLinks.tinyId("AQAAAA")).isEqualTo("1");
    }

    @Test
    void aConfluenceWithoutContextPathAcceptsRootRelativeAddresses() {
        assertThat(ConfluenceStorageLinks.href("/pages/viewpage.action?pageId=5", "wiki.example.org"))
                .isEqualTo(LinkTarget.page("5"));
        assertThat(ConfluenceStorageLinks.href("//wiki.example.org/pages/viewpage.action?pageId=5",
                "wiki.example.org")).isNull();
    }
}
