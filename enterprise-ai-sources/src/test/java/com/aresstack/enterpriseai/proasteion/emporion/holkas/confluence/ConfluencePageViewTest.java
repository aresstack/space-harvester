package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Die Lese-Ansicht behält die Bilder an ihrer Stelle und listet sie in Dokumentreihenfolge. */
public class ConfluencePageViewTest {

    private static final String BASE = "https://confluence.invalid/confluence/";

    @Test
    public void imagesStayInlineAndAreListedInDocumentOrder() {
        String body = "<h2>Überblick</h2>"
                + "<p>Vorher <img class=\"confluence-embedded-image\" src=\"/confluence/download/attachments/7/a.png"
                + "?version=2&amp;api=v2\" alt=\"Architektur\" data-linked-resource-id=\"101\""
                + " data-linked-resource-version=\"2\" data-linked-resource-type=\"attachment\""
                + " data-linked-resource-content-type=\"image/png\" data-linked-resource-container-id=\"7\"> nachher</p>"
                + "<p><img src=\"/confluence/images/icons/emoticons/smile.svg\" alt=\"(smile)\"></p>"
                + "<p><img src=\"https://other.invalid/logo.gif\"></p>"
                + "<script>alert(1)</script>"
                + "<div class=\"hidden\">Lade Vorgänge</div><p style=\"display: none\">Platzhalter</p>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertTrue(view.html, view.html.contains("Vorher <img"));
        assertTrue(view.html, view.html.contains("src=\"https://confluence.invalid/confluence/download/attachments/7/a.png"));
        assertFalse(view.html, view.html.contains("smile.svg")); // Emoticon ist kein Bild, nur sein Text bleibt
        assertTrue(view.html, view.html.contains("<p>(smile)</p>"));
        assertFalse(view.html, view.html.contains("<script"));
        assertFalse(view.html, view.html.contains("Lade Vorgänge"));
        assertFalse(view.html, view.html.contains("Platzhalter"));
        assertTrue(view.html, view.html.contains("id=\"überblick\""));

        assertEquals(2, view.images.size());
        ConfluencePageView.InlineImage first = view.images.get(0);
        assertEquals("https://confluence.invalid/confluence/download/attachments/7/a.png?version=2&api=v2", first.src);
        assertEquals("att101", first.attachmentId);
        assertEquals("2", first.version);
        assertEquals("image/png", first.mediaType);
        assertEquals("Architektur", first.title);
        assertEquals("https://other.invalid/logo.gif", view.images.get(1).src);
        assertNull(view.images.get(1).attachmentId);
    }

    @Test
    public void aDrawioDiagramShowsItsPreviewAttachment() {
        String body = "<p>Text</p><p><div style=\"display:block;\" class=\"conf-macro output-block\""
                + " data-macro-name=\"drawio-sketch\"><div style=\"display:none\">Übersicht A</div>"
                + "<div class=\"drawio-macro\" style=\"width:300px;height:200px;\"></div>"
                + "<div style=\"display:none\" data-diagramdata=\"" + diagram("Übersicht A", 42, 3) + "\"></div>"
                + "</div></p><p>Danach</p>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertEquals(1, view.images.size());
        assertEquals(BASE + "download/attachments/42/%C3%9Cbersicht%20A.png?version=3&api=v2"
                + "#drawio=%C3%9Cbersicht%20A&page=42&revision=3", view.images.get(0).src);
        assertEquals("Übersicht A", view.images.get(0).title);
        assertTrue(view.html, view.html.indexOf("%C3%9Cbersicht%20A.png") > view.html.indexOf("Text")
                && view.html.indexOf("%C3%9Cbersicht%20A.png") < view.html.indexOf("Danach"));
        assertFalse("Diagrammdaten nicht in der Ansicht", view.html.contains("data-diagramdata"));
    }

    @Test
    public void anIncludedDiagramLoadsFromItsOwningPage() {
        String body = "<div data-macro-name=\"inc-drawio\"><div style=\"display:none\" data-diagramdata=\""
                + diagram("Fremd", 99, 1) + "\"></div></div>"
                + "<div data-macro-name=\"drawio\"><div style=\"display:none\" data-diagramdata=\""
                + diagram("Eigen", 42, 7) + "\"></div></div>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertEquals(2, view.images.size());
        assertEquals(BASE + "download/attachments/99/Fremd.png?version=1&api=v2#drawio=Fremd&page=99&revision=1",
                view.images.get(0).src);
        assertEquals(BASE + "download/attachments/42/Eigen.png?version=7&api=v2#drawio=Eigen&page=42&revision=7",
                view.images.get(1).src);
    }

    @Test
    public void fourDiagramsOnAPageAreFourImagesInPageOrder() {
        StringBuilder body = new StringBuilder();
        String[] macros = {"drawio", "drawio-sketch", "inc-drawio", "drawio"};
        for (int i = 0; i < macros.length; i++) {
            body.append("<p>Abschnitt ").append(i).append("</p><div data-macro-name=\"").append(macros[i])
                    .append("\"><div class=\"drawio-macro\"></div><div style=\"display:none\" data-diagramdata=\"")
                    .append(diagram("Bild " + i, 40 + i, i + 1)).append("\"></div></div>");
        }

        ConfluencePageView view = ConfluencePageView.of(body.toString(), BASE);

        assertEquals(4, view.images.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(BASE + "download/attachments/" + (40 + i) + "/Bild%20" + i + ".png?version=" + (i + 1)
                    + "&api=v2#drawio=Bild%20" + i + "&page=" + (40 + i) + "&revision=" + (i + 1), view.images.get(i).src);
            assertTrue(view.html, view.html.indexOf("Abschnitt " + i) < view.html.indexOf("/Bild%20" + i + ".png"));
        }
    }

    @Test
    public void aDrawioImageWithAPluginAddressPointsToThePreview() {
        String body = "<div data-macro-name=\"drawio\"><img src=\"/confluence/plugins/servlet/drawio/x?pageId=42\">"
                + "<div style=\"display:none\" data-diagramdata=\"" + diagram("Ablauf", 42, 2) + "\"></div></div>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertEquals(1, view.images.size());
        assertEquals(BASE + "download/attachments/42/Ablauf.png?version=2&api=v2#drawio=Ablauf&page=42&revision=2",
                view.images.get(0).src);
        assertEquals("Ablauf", view.images.get(0).title);
    }

    @Test
    public void withoutANumericRevisionTheLatestPreviewIsRequested() {
        String data = java.util.Base64.getEncoder().encodeToString(
                "{\"diagramName\":\"Ohne\",\"previewName\":\"Ohne.png\",\"owningPageId\":42,\"revision\":\"x\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String body = "<div data-macro-name=\"drawio\"><div style=\"display:none\" data-diagramdata=\"" + data
                + "\"></div></div>";

        assertEquals(BASE + "download/attachments/42/Ohne.png#drawio=Ohne&page=42",
                ConfluencePageView.of(body, BASE).images.get(0).src);
    }

    private static String diagram(String name, int owningPageId, int revision) {
        return java.util.Base64.getEncoder().encodeToString(("{\"diagramName\":\"" + name + "\",\"previewName\":\""
                + name + ".png\",\"owningPageId\":" + owningPageId + ",\"revision\":" + revision + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void aDrawioMacroThatAlreadyHasAnImageGetsNoSecondOne() {
        String data = java.util.Base64.getEncoder().encodeToString(
                "{\"previewName\":\"D.png\",\"owningPageId\":7}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String body = "<div data-macro-name=\"drawio\"><img src=\"/confluence/download/attachments/7/D.png\">"
                + "<div style=\"display:none\" data-diagramdata=\"" + data + "\"></div></div>";

        assertEquals(1, ConfluencePageView.of(body, BASE).images.size());
    }

    /** Wie Confluence es liefert: das Makro bringt sein Vorschau-PNG schon mit, die Zeichnung zählt trotzdem. */
    @Test
    public void aDeliveredPreviewImageInsideTheMacroIsStillMarkedAsDrawio() {
        String body = "<div class=\"drawio-macro\" data-macro-name=\"drawio\">"
                + "<img src=\"/confluence/download/attachments/42/Bild%20A.png?version=55&amp;modificationDate=1700000000000"
                + "&amp;api=v2\" srcset=\"/confluence/download/attachments/42/Bild%20A.png?version=55 2x\">"
                + "<div style=\"display:none\" data-diagramdata=\"" + diagram("Bild A", 42, 55) + "\"></div></div>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertEquals(1, view.images.size());
        assertEquals(BASE + "download/attachments/42/Bild%20A.png?version=55&api=v2#drawio=Bild%20A&page=42&revision=55",
                view.images.get(0).src);
    }

    @Test
    public void aDeliveredPreviewImageNextToTheMacroIsStillMarkedAsDrawio() {
        String body = "<p><span class=\"confluence-embedded-file-wrapper\"><img class=\"confluence-embedded-image\""
                + " src=\"/confluence/download/attachments/42/Bild%20A.png?version=55&amp;modificationDate=1700000000000"
                + "&amp;api=v2\"></span></p>"
                + "<div data-macro-name=\"drawio\"><div style=\"display:none\" data-diagramdata=\""
                + diagram("Bild A", 42, 55) + "\"></div></div>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertEquals(1, view.images.size());
        assertEquals(BASE + "download/attachments/42/Bild%20A.png?version=55&api=v2#drawio=Bild%20A&page=42&revision=55",
                view.images.get(0).src);
    }

    /** export_view ohne Diagrammdaten, nur das Vorschaubild: Name und Revision kommen aus body.storage. */
    @Test
    public void withoutDiagramDataTheMacroParametersFromStorageMarkThePreview() {
        String body = "<p><span class=\"confluence-embedded-file-wrapper\"><img class=\"confluence-embedded-image\""
                + " src=\"/confluence/download/attachments/42/Bild%20A.png?version=55&amp;modificationDate=1700000000000"
                + "&amp;api=v2\"></span></p><p><img src=\"/confluence/download/attachments/42/Foto.png\"></p>"
                + "<p><img src=\"/confluence/download/attachments/99/Fremd.png?version=2\"></p>";
        String storage = "<p><ac:structured-macro ac:name=\"drawio\" ac:schema-version=\"1\">"
                + "<ac:parameter ac:name=\"diagramName\">Bild A</ac:parameter>"
                + "<ac:parameter ac:name=\"revision\">55</ac:parameter></ac:structured-macro></p>"
                + "<ac:structured-macro ac:name=\"inc-drawio\"><ac:parameter ac:name=\"diagramName\">Fremd"
                + "</ac:parameter><ac:parameter ac:name=\"pageId\">99</ac:parameter>"
                + "<ac:parameter ac:name=\"revision\">2</ac:parameter></ac:structured-macro>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE, null, storage, "42");

        assertEquals(3, view.images.size());
        assertEquals(BASE + "download/attachments/42/Bild%20A.png?version=55&api=v2#drawio=Bild%20A&page=42&revision=55",
                view.images.get(0).src);
        assertEquals("ein gewöhnliches Bild bleibt gewöhnlich", BASE + "download/attachments/42/Foto.png",
                view.images.get(1).src);
        assertEquals(BASE + "download/attachments/99/Fremd.png?version=2&api=v2#drawio=Fremd&page=99&revision=2",
                view.images.get(2).src);
    }

    @Test
    public void spacesAndUmlautsInImageAddressesAreEncoded() {
        ConfluencePageView view = ConfluencePageView.of(
                "<img src=\"/confluence/download/attachments/7/Bild mit Ü.png?version=1\">", BASE);

        assertEquals(BASE + "download/attachments/7/Bild%20mit%20%C3%9C.png?version=1", view.images.get(0).src);
    }

    // ------------------------------------------------------------------ Symbole sind keine Inhaltsbilder

    private static final String JIRA = "https://jira.invalid/browse/PROJ-12345";

    /** Regressionstest 1: Jira-Link mit Symbol. Das Symbol fällt weg, Link und Vorgangsschlüssel bleiben. */
    @Test
    public void aJiraIconInsideItsLinkIsNoImageButTheLinkStays() {
        String body = "<p><a href=\"" + JIRA + "\"><img src=\"/images/icons/jira-issue.png\"> PROJ-12345</a></p>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertTrue(view.images.isEmpty());
        assertFalse(view.html, view.html.contains("<img"));
        assertTrue(view.html, view.html.contains("<a href=\"" + JIRA + "\"> PROJ-12345</a>"));
    }

    /** Wie Confluence das Jira-Makro liefert: Avatar vom Jira-Host, kein {@code /icons/} im Pfad. */
    @Test
    public void aJiraMacroAvatarFromTheJiraHostIsNoImage() {
        String body = "<p><span class=\"confluence-jim-macro jira-issue\" data-jira-key=\"PROJ-12345\">"
                + "<a href=\"" + JIRA + "\" class=\"jira-issue-key\"><img class=\"icon\""
                + " src=\"https://jira.invalid/secure/viewavatar?size=xsmall&amp;avatarId=10315&amp;avatarType=issuetype\">"
                + "PROJ-12345</a> - <span class=\"summary\">Zusammenfassung</span></span></p>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertTrue(view.images.isEmpty());
        assertFalse(view.html, view.html.contains("<img"));
        assertTrue(view.html, view.html.contains("class=\"jira-issue-key\">PROJ-12345</a>"));
        assertTrue(view.html, view.html.contains("Zusammenfassung"));
    }

    /** Regressionstest 2: Emoticon und Icon ohne Link. Kein Bild bleibt; das Emoticon wird zu seinem Text. */
    @Test
    public void emoticonsAndIconsWithoutALinkLeaveNoImage() {
        String body = "<table><tr><td><img class=\"emoticon emoticon-tick\" data-emoticon-name=\"tick\""
                + " src=\"/confluence/images/icons/emoticons/check.svg\" alt=\"(/)\"></td>"
                + "<td><img src=\"/confluence/images/icons/contenttypes/page.png\" alt=\"\"> Seite</td>"
                + "<td><img src=\"data:image/png;base64,iVBORw0KGgo=\"> Daten</td></tr></table>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertTrue(view.images.isEmpty());
        assertFalse(view.html, view.html.contains("<img"));
        assertTrue(view.html, view.html.contains("<td>(/)</td>"));
        assertTrue(view.html, view.html.contains("Seite"));
    }

    /** Regressionstest 3 und 4: Inhaltsbild und draw.io neben einem Jira-Symbol bleiben, wie sie waren. */
    @Test
    public void contentImagesAndDrawioStayNextToAJiraIcon() {
        String body = "<p><img class=\"confluence-embedded-image\" src=\"/confluence/download/attachments/7/a.png\""
                + " alt=\"Architektur\" data-linked-resource-id=\"101\"></p>"
                + "<p><a href=\"" + JIRA + "\"><img src=\"/images/icons/jira-issue.png\"> PROJ-12345</a></p>"
                + "<div data-macro-name=\"drawio\"><div style=\"display:none\" data-diagramdata=\""
                + diagram("Ablauf", 42, 3) + "\"></div></div>";

        ConfluencePageView view = ConfluencePageView.of(body, BASE);

        assertEquals(2, view.images.size());
        assertEquals(BASE + "download/attachments/7/a.png", view.images.get(0).src);
        assertEquals("att101", view.images.get(0).attachmentId);
        assertEquals(BASE + "download/attachments/42/Ablauf.png?version=3&api=v2#drawio=Ablauf&page=42&revision=3",
                view.images.get(1).src);
        assertTrue(view.html, view.html.contains("src=\"" + BASE + "download/attachments/7/a.png\""));
        assertTrue(view.html, view.html.contains("#drawio=Ablauf&amp;page=42&amp;revision=3"));
        assertFalse(view.html, view.html.contains("jira-issue.png"));
    }
}
