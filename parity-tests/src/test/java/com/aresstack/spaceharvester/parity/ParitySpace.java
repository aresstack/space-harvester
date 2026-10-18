package com.aresstack.spaceharvester.parity;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;

/**
 * Paritäts-Space {@code PARITY} für {@link FakeConfluence} (ADR 0012, docs/PARITY.md, „Vergleichstests“, #193).
 *
 * <p>Dieselbe Klasse füllt den Fake für den Core (Test {@code CoreParityTest}) und für den Enterprise AI Client
 * ({@code parity-tests/eac/generate.sh} übersetzt sie mit {@code --release 8} gegen dessen Fake). Deshalb nur Java 8
 * und nur die API von {@link FakeConfluence}, die in beiden Repositories gleich ist.</p>
 *
 * <p>Jede Seite trägt {@code body.storage} (Quelle des Core) und eine nachgebildete {@code body.export_view}
 * (Quelle des Enterprise AI Client). Die Leseansicht ist von Hand geschrieben, nicht von Confluence gerendert;
 * maßgeblich für echte Makros bleibt der Live-Lauf mit {@code ParityReport}.</p>
 *
 * <p>Inhalt: Hierarchie bis Ebene 7, Tabelle mit Kopfzeile, Listen, Durchgestrichenes, Codeblock, externe Links und
 * {@code mailto:}, interner Seitenlink, Bild als Anhang (SVG), externes Bild, fehlendes Bild, Mermaid, draw.io und
 * ausgeführte Makros ({@code include}, {@code excerpt-include}, {@code children}).
 * Binäre Bilder (PNG) gehen nicht, weil der Fake Anhänge als UTF-8-Text speichert.</p>
 */
public final class ParitySpace {

    public static final String SPACE = "PARITY";
    public static final String SPACE_NAME = "Paritätsprüfung";
    public static final String HOME = "1001";
    /** Alle Seiten des Space, für den Vergleich der Leseansicht. */
    public static final String[] PAGES = {"1001", "1002", "1003", "1004", "1005", "1006", "1007", "1008", "1009",
            "1010"};

    /** Sätze, die in jedem Export vorkommen müssen; je Seite einer. */
    public static final String[] SENTENCES = {
            "Satz Alpha der Einleitung.",
            "Satz Bravo zum Zweck.",
            "Satz Charlie zur Architektur.",
            "Satz Echo zu den Komponenten.",
            "Satz Foxtrot zum Betrieb.",
            "Satz Ebene vier.",
            "Satz Ebene sieben.",
            "Satz Golf vor den Makros.",
            "Satz Juliett.",
    };

    /**
     * Sätze, die nur ein ausgeführtes Makro ({@code include}, {@code excerpt-include}) in die Seite „Makros“ bringt:
     * Sie stehen auf einer Seite eines anderen Space und nur in der Leseansicht von „Makros“ (#218).
     */
    public static final String[] MACRO_SENTENCES = {
            "Satz Hotel aus dem Baustein.",
            "Satz India aus dem Auszug.",
    };

    static final String SYSTEM_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"60\">"
            + "<rect x=\"0\" y=\"0\" width=\"120\" height=\"60\" fill=\"#3366cc\"/>"
            + "<text x=\"10\" y=\"35\" fill=\"#ffffff\">System</text></svg>";

    static final String DRAWIO = "<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
            + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
            + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel></diagram></mxfile>";

    static final String MERMAID = "graph TD\n  A[Quelle] --> B[Export]";

    private ParitySpace() {
    }

    public static FakeConfluence install(FakeConfluence confluence) {
        confluence.space(SPACE, SPACE_NAME);

        page(confluence, HOME, "Handbuch",
                "<p>Satz Alpha der Einleitung.</p><h1>Zweck</h1><p>Satz Bravo zum Zweck.</p>",
                "<p>Satz Alpha der Einleitung.</p><h1 id=\"Handbuch-Zweck\">Zweck</h1><p>Satz Bravo zum Zweck.</p>");
        confluence.homepage(SPACE, HOME);

        page(confluence, "1002", "Architektur",
                "<p>Satz Charlie zur Architektur. Siehe <a href=\"https://example.org/doku\">Dokumentation</a>"
                        + " und <a href=\"mailto:team@example.org\">Team</a>.</p>"
                        + table()
                        + lists()
                        + "<p><s>veraltet</s> <strong>fett</strong> <em>kursiv</em></p>"
                        + "<ac:structured-macro ac:name=\"code\" ac:schema-version=\"1\">"
                        + "<ac:parameter ac:name=\"language\">java</ac:parameter>"
                        + "<ac:plain-text-body><![CDATA[System.out.println(\"Delta\");]]></ac:plain-text-body>"
                        + "</ac:structured-macro>"
                        + "<p><ac:image ac:alt=\"Systembild\"><ri:attachment ri:filename=\"system.svg\"/></ac:image></p>",
                "<p>Satz Charlie zur Architektur. Siehe <a href=\"https://example.org/doku\" class=\"external-link\">"
                        + "Dokumentation</a> und <a href=\"mailto:team@example.org\" class=\"external-link\">Team</a>."
                        + "</p>"
                        + table().replace("<table>", "<table class=\"confluenceTable\">")
                        + lists()
                        + "<p><s>veraltet</s> <strong>fett</strong> <em>kursiv</em></p>"
                        + "<div class=\"code panel pdl\"><div class=\"codeContent panelContent pdl\">"
                        + "<pre class=\"syntaxhighlighter-pre\" data-syntaxhighlighter-params=\"brush: java\">"
                        + "System.out.println(&quot;Delta&quot;);</pre></div></div>"
                        + "<p>" + image("1002", "system.svg", "2001", "image/svg+xml", "Systembild") + "</p>");
        confluence.child(HOME, "1002");
        confluence.attachment("att2001", "1002", "system.svg", "image/svg+xml", SYSTEM_SVG);

        page(confluence, "1003", "Komponenten",
                "<p>Satz Echo zu den Komponenten. Weiter bei <ac:link><ri:page ri:content-title=\"Betrieb\"/>"
                        + "<ac:plain-text-link-body><![CDATA[Betrieb]]></ac:plain-text-link-body></ac:link>.</p>"
                        + "<ac:structured-macro ac:name=\"mermaid-macro\" ac:schema-version=\"1\">"
                        + "<ac:plain-text-body><![CDATA[" + MERMAID + "]]></ac:plain-text-body>"
                        + "</ac:structured-macro>",
                "<p>Satz Echo zu den Komponenten. Weiter bei <a href=\"/wiki/display/PARITY/Betrieb\""
                        + " data-linked-resource-id=\"1004\" data-linked-resource-type=\"page\">Betrieb</a>.</p>"
                        + "<div class=\"code panel pdl\"><div class=\"codeContent panelContent pdl\">"
                        + "<pre class=\"syntaxhighlighter-pre\" data-syntaxhighlighter-params=\"brush: mermaid\">"
                        + MERMAID.replace(">", "&gt;") + "</pre></div></div>");
        confluence.child("1002", "1003");

        String parent = "1003";
        String[] levels = {"vier", "fünf", "sechs", "sieben"};
        for (int i = 0; i < levels.length; i++) {
            String id = "100" + (5 + i);
            String body = "<p>Satz Ebene " + levels[i] + ".</p>";
            page(confluence, id, "Ebene " + (4 + i), body, body);
            confluence.child(parent, id);
            parent = id;
        }

        page(confluence, "1004", "Betrieb",
                "<p>Satz Foxtrot zum Betrieb.</p>"
                        + "<p><ac:image><ri:attachment ri:filename=\"fehlt.png\"/></ac:image></p>"
                        + "<p><ac:image><ri:url ri:value=\"https://images.example.org/logo.svg\"/></ac:image></p>"
                        + "<ac:structured-macro ac:name=\"drawio\" ac:schema-version=\"1\">"
                        + "<ac:parameter ac:name=\"diagramName\">ablauf</ac:parameter>"
                        + "<ac:parameter ac:name=\"revision\">1</ac:parameter>"
                        + "</ac:structured-macro>",
                "<p>Satz Foxtrot zum Betrieb.</p>"
                        + "<p>" + image("1004", "fehlt.png", "2999", "image/png", "fehlt.png") + "</p>"
                        + "<p><img class=\"confluence-embedded-image confluence-external-resource\""
                        + " src=\"https://images.example.org/logo.svg\"></p>"
                        + "<div class=\"drawio-macro\" data-diagramname=\"ablauf\" data-pageid=\"1004\""
                        + " data-revision=\"1\"></div>");
        confluence.child(HOME, "1004");
        confluence.attachment("att2002", "1004", "ablauf", "application/vnd.jgraph.mxfile", DRAWIO);

        // Makros, deren Inhalt erst Confluence beim Rendern einsetzt (#218): include und excerpt-include einer Seite
        // aus einem anderen Space, children mit der Kindseite „Juliett“
        page(confluence, "1009", "Makros",
                "<p>Satz Golf vor den Makros.</p>"
                        + macro("include", "<ac:parameter ac:name=\"\"><ac:link><ri:page ri:space-key=\"LIB\""
                        + " ri:content-title=\"Baustein\"/></ac:link></ac:parameter>")
                        + macro("excerpt-include", "<ac:parameter ac:name=\"\"><ac:link><ri:page"
                        + " ri:space-key=\"LIB\" ri:content-title=\"Baustein\"/></ac:link></ac:parameter>")
                        + macro("children", ""),
                "<p>Satz Golf vor den Makros.</p>"
                        + rendered("include", "<p>" + MACRO_SENTENCES[0] + "</p>")
                        + rendered("excerpt-include", "<p>" + MACRO_SENTENCES[1] + "</p>")
                        + rendered("children", "<ul class=\"childpages-macro\"><li>"
                        + "<a href=\"/wiki/spaces/PARITY/pages/1010/Juliett\" data-linked-resource-id=\"1010\""
                        + " data-linked-resource-type=\"page\">Juliett</a></li></ul>"));
        confluence.child(HOME, "1009");
        page(confluence, "1010", "Juliett", "<p>Satz Juliett.</p>", "<p>Satz Juliett.</p>");
        confluence.child("1009", "1010");
        return confluence;
    }

    private static void page(FakeConfluence confluence, String id, String title, String storage, String view) {
        confluence.page(id, title, SPACE, view).storage(storage).exportView(view).version(1, "2026-10-18T00:00:00.000Z");
    }

    private static String macro(String name, String parameters) {
        return "<ac:structured-macro ac:name=\"" + name + "\" ac:schema-version=\"1\">" + parameters
                + "</ac:structured-macro>";
    }

    /** Ein von Confluence gerendertes Makro in der Leseansicht. */
    private static String rendered(String name, String content) {
        return "<div class=\"conf-macro output-block\" data-hasbody=\"false\" data-macro-name=\"" + name + "\">"
                + content + "</div>";
    }

    private static String table() {
        return "<table><tbody>"
                + "<tr><th>Name</th><th>Rolle</th><th>Ort</th></tr>"
                + "<tr><td>Leser</td><td>liest</td><td>Berlin</td></tr>"
                + "<tr><td>Schreiber</td><td>schreibt</td><td>Bonn</td></tr>"
                + "</tbody></table>";
    }

    private static String lists() {
        return "<ul><li>Punkt eins<ul><li>Unterpunkt</li></ul></li><li>Punkt zwei</li></ul>"
                + "<ol><li>Schritt eins</li><li>Schritt zwei</li></ol>";
    }

    private static String image(String pageId, String file, String attachmentId, String type, String alt) {
        String src = "/wiki/download/attachments/" + pageId + "/" + file + "?version=1&amp;api=v2";
        return "<span class=\"confluence-embedded-file-wrapper\"><img class=\"confluence-embedded-image\""
                + " alt=\"" + alt + "\" src=\"" + src + "\" data-image-src=\"" + src + "\""
                + " data-linked-resource-id=\"" + attachmentId + "\" data-linked-resource-version=\"1\""
                + " data-linked-resource-type=\"attachment\" data-linked-resource-default-alias=\"" + file + "\""
                + " data-linked-resource-content-type=\"" + type + "\""
                + " data-linked-resource-container-id=\"" + pageId + "\"></span>";
    }
}
