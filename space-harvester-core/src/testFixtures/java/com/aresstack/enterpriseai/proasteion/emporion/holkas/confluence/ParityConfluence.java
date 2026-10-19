package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Paritäts-Space {@code PARITY} „Paritätsprüfung“ für {@link FakeConfluence} (#193, ADR 0012, docs/PARITY.md,
 * „Vergleichstests“): dieselben Testdaten für den Enterprise AI Client und den Core.
 *
 * <p>Liegt im Paket von {@link FakeConfluence} und braucht nur Java 8, damit die Datei unverändert in die
 * Test-Fixtures von {@code source-confluence} im Enterprise AI Client übernommen werden kann. Im Core liegt sie in den
 * Test-Fixtures von {@code space-harvester-core}, weil {@code enterprise-ai-sources} eine unveränderte Kopie bleibt.
 * Aus dem Paket nutzt sie nur, was in beiden Repositories gleich ist; binäre Anhänge (PDF, DOCX) legt sie direkt
 * an, weil {@link FakeConfluence#attachment} Inhalte als UTF-8-Text nimmt.</p>
 *
 * <p>Jede Seite trägt {@code body.storage} (Quelle des Core) und eine nachgebildete {@code body.export_view} (Quelle
 * des Enterprise AI Client); {@code body.view} ist dieselbe Ansicht, nur Jira steht dort wie bei Confluence als
 * Platzhalter. Die Ansichten sind von Hand geschrieben, nicht von Confluence gerendert; maßgeblich für echte Makros
 * bleibt der Live-Lauf.</p>
 *
 * <p>Inhalt:</p>
 * <ul>
 *   <li>Hierarchie bis Ebene 7: Handbuch → Architektur → Komponenten → Ebene 4 … Ebene 7, daneben Betrieb, Makros
 *       (mit Kind Juliett) und Anhänge.</li>
 *   <li>Tabelle 3 × 3 mit Kopfzeile, verschachtelte und nummerierte Listen, Hervorhebungen, Code-Makro.</li>
 *   <li>Externer Link und {@code mailto:}, interner Seitenlink ({@code ac:link}), Verweise auf Anhänge.</li>
 *   <li>Bilder: als Anhang (SVG), extern (nicht ladbar), inline im Fließtext (Vorschaubild eines Anhangs), fehlend
 *       (404), dazu ein Emoticon und ein {@code data:}-Bild, die keine Inhaltsbilder sind.</li>
 *   <li>draw.io mit Revision ({@code drawio}) und dasselbe Diagramm von einer anderen Seite ({@code inc-drawio}),
 *       Mermaid.</li>
 *   <li>Ausgeführte Makros: {@code include} und {@code excerpt-include} einer Seite aus einem anderen Space,
 *       {@code children}, Jira-Vorgang.</li>
 *   <li>Anhänge PDF und DOCX mit je einem Merksatz.</li>
 * </ul>
 */
public final class ParityConfluence {

    public static final String SPACE = "PARITY";
    public static final String SPACE_NAME = "Paritätsprüfung";
    public static final String HOME = "1001";
    /** Wurzel der Exportumfänge (#186): „Architektur“ mit der Kette „Komponenten“ → „Ebene 4“ … „Ebene 7“ darunter. */
    public static final String SCOPE_ROOT = "1002";
    /** Seite mit Anhängen, Inline-Bild, {@code inc-drawio} und Jira. */
    public static final String ATTACHMENTS_PAGE = "1011";
    /** Alle Seiten des Space in Baumreihenfolge. */
    public static final String[] PAGES = {"1001", "1002", "1003", "1005", "1006", "1007", "1008", "1004", "1009",
            "1010", "1011"};
    /** Tiefste Gliederungsebene unter dem Space (Handbuch = 1, Ebene 7 = 7). */
    public static final int DEPTH = 7;

    /** Sätze, die in jedem Export und jeder Leseansicht vorkommen müssen; je Seite mindestens einer. */
    public static final String[] SENTENCES = {
            "Satz Alpha der Einleitung.",
            "Satz Bravo zum Zweck.",
            "Satz Charlie zur Architektur.",
            "Satz Echo zu den Komponenten.",
            "Satz Foxtrot zum Betrieb.",
            "Satz Ebene vier.",
            "Satz Ebene fünf.",
            "Satz Ebene sechs.",
            "Satz Ebene sieben.",
            "Satz Golf vor den Makros.",
            "Satz Juliett.",
            "Satz Kilo zu den Anhängen.",
            "Satz Lima mit Symbol im Text.",
            "Satz Mike nach dem Symbol.",
    };

    /**
     * Sätze, die nur ein ausgeführtes Makro ({@code include}, {@code excerpt-include}) in die Seite „Makros“ bringt:
     * Sie stehen auf einer Seite eines anderen Space und nur in der Leseansicht von „Makros“ (#218).
     */
    public static final String[] MACRO_SENTENCES = {
            "Satz Hotel aus dem Baustein.",
            "Satz India aus dem Auszug.",
    };

    /** Jira-Vorgang auf der Seite „Anhänge“: Schlüssel und Zusammenfassung, nur in {@code body.export_view}. */
    public static final String JIRA_KEY = "PAR-7";
    public static final String JIRA_SENTENCE = "Satz Papa aus Jira.";

    /** Anhänge der Seite „Anhänge“ als Dokument: Dateiname, Medientyp, Merksatz im Dokument. */
    public static final String[][] DOCUMENTS = {
            {"handbuch.pdf", "application/pdf", "Satz November im PDF."},
            {"vorlage.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "Satz Oscar im Word-Dokument."},
    };

    /** Suchbegriff der Live-Suche in Confluence und die Seiten, die sie liefert. */
    public static final String LIVE_SEARCH = "Architektur";
    public static final String[] LIVE_HITS = {"1002", "1003"};

    /** Bilder je Seite: Anhang, extern, inline und fehlend; draw.io wird zusätzlich gezeichnet. */
    public static final String SYSTEM_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"60\">"
            + "<rect x=\"0\" y=\"0\" width=\"120\" height=\"60\" fill=\"#3366cc\"/>"
            + "<text x=\"10\" y=\"35\" fill=\"#ffffff\">System</text></svg>";

    public static final String SYMBOL_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\">"
            + "<circle cx=\"8\" cy=\"8\" r=\"7\" fill=\"#cc3333\"/></svg>";

    public static final String DRAWIO = "<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
            + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
            + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel></diagram></mxfile>";

    public static final String MERMAID = "graph TD\n  A[Quelle] --> B[Export]";

    private static final String MODIFIED = "2026-10-18T00:00:00.000Z";

    private ParityConfluence() {
    }

    /** Legt den Space in {@code confluence} an und gibt den Fake zurück. */
    public static FakeConfluence install(FakeConfluence confluence) {
        confluence.space(SPACE, SPACE_NAME);

        page(confluence, HOME, "Handbuch",
                "<p>Satz Alpha der Einleitung.</p><h1>Zweck</h1><p>Satz Bravo zum Zweck.</p>",
                "<p>Satz Alpha der Einleitung.</p><h1 id=\"Handbuch-Zweck\">Zweck</h1><p>Satz Bravo zum Zweck.</p>");
        confluence.homepage(SPACE, HOME);
        confluence.searchResults(LIVE_HITS);

        page(confluence, "1002", "Architektur",
                "<p>Satz Charlie zur Architektur. Siehe <a href=\"https://example.org/doku\">Dokumentation</a>"
                        + " und <a href=\"mailto:team@example.org\">Team</a>.</p>"
                        + table()
                        + lists()
                        + "<p><s>veraltet</s> <strong>fett</strong> <em>kursiv</em></p>"
                        + macro("code", "<ac:parameter ac:name=\"language\">java</ac:parameter>"
                        + "<ac:plain-text-body><![CDATA[System.out.println(\"Delta\");]]></ac:plain-text-body>")
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
                        + "<p>" + image("1002", "system.svg", "2001", "image/svg+xml", "Systembild", false) + "</p>");
        confluence.child(HOME, "1002");
        confluence.attachment("att2001", "1002", "system.svg", "image/svg+xml", SYSTEM_SVG);

        page(confluence, "1003", "Komponenten",
                "<p>Satz Echo zu den Komponenten. Weiter bei <ac:link><ri:page ri:content-title=\"Betrieb\"/>"
                        + "<ac:plain-text-link-body><![CDATA[Betrieb]]></ac:plain-text-link-body></ac:link>.</p>"
                        + macro("mermaid-macro", "<ac:plain-text-body><![CDATA[" + MERMAID + "]]></ac:plain-text-body>"),
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
                        + macro("drawio", "<ac:parameter ac:name=\"diagramName\">ablauf</ac:parameter>"
                        + "<ac:parameter ac:name=\"revision\">1</ac:parameter>"),
                "<p>Satz Foxtrot zum Betrieb.</p>"
                        + "<p>" + image("1004", "fehlt.png", "2999", "image/png", "fehlt.png", false) + "</p>"
                        + "<p><img class=\"confluence-embedded-image confluence-external-resource\""
                        + " src=\"https://images.example.org/logo.svg\"></p>"
                        + drawio("1004"));
        confluence.child(HOME, "1004");
        confluence.attachment("att2002", "1004", "ablauf", "application/vnd.jgraph.mxfile", DRAWIO);

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

        attachmentsPage(confluence);
        confluence.child(HOME, ATTACHMENTS_PAGE);
        return confluence;
    }

    /** Seite „Anhänge“: Inline-Bild, Emoticon, {@code data:}-Bild, {@code inc-drawio}, Jira, PDF und DOCX. */
    private static void attachmentsPage(FakeConfluence confluence) {
        String jira = macro("jira", "<ac:parameter ac:name=\"server\">Jira</ac:parameter>"
                + "<ac:parameter ac:name=\"key\">" + JIRA_KEY + "</ac:parameter>");
        String links = "<ul><li><ac:link><ri:attachment ri:filename=\"handbuch.pdf\"/></ac:link></li>"
                + "<li><ac:link><ri:attachment ri:filename=\"vorlage.docx\"/></ac:link></li></ul>";
        String storage = "<p>Satz Kilo zu den Anhängen.</p>"
                + "<p>Satz Lima mit Symbol im Text. <ac:image ac:thumbnail=\"true\" ac:height=\"16\">"
                + "<ri:attachment ri:filename=\"symbol.svg\"/></ac:image> Satz Mike nach dem Symbol.</p>"
                + "<p><ac:emoticon ac:name=\"smile\"/></p>"
                + macro("inc-drawio", "<ac:parameter ac:name=\"diagramName\">ablauf</ac:parameter>"
                + "<ac:parameter ac:name=\"pageId\">1004</ac:parameter>"
                + "<ac:parameter ac:name=\"revision\">1</ac:parameter>")
                + "<p>" + jira + "</p>"
                + links;
        String before = "<p>Satz Kilo zu den Anhängen.</p>"
                + "<p>Satz Lima mit Symbol im Text. "
                + image(ATTACHMENTS_PAGE, "symbol.svg", "2003", "image/svg+xml", "symbol.svg", true)
                + " Satz Mike nach dem Symbol.</p>"
                + "<p><img class=\"emoticon emoticon-smile\" src=\"/wiki/images/icons/emoticons/smile.svg\""
                + " alt=\"(Lächeln)\"> <img src=\"data:image/gif;base64,R0lGODlhAQABAAAAACw=\" alt=\"\"></p>"
                + drawio("1004");
        String after = "<ul><li>" + attachmentLink("handbuch.pdf", "2004", DOCUMENTS[0][1]) + "</li>"
                + "<li>" + attachmentLink("vorlage.docx", "2005", DOCUMENTS[1][1]) + "</li></ul>";
        // body.view lässt Jira wie Confluence als Platzhalter stehen; export_view enthält den Vorgang.
        String view = before + "<p><span class=\"jira-issue conf-macro output-block\" data-jira-key=\"" + JIRA_KEY
                + "\"><a class=\"jira-issue-key\" href=\"https://jira.example.org/browse/" + JIRA_KEY + "\">"
                + "<span class=\"aui-icon aui-icon-wait\">Loading</span>" + JIRA_KEY + "</a> - "
                + "<span class=\"summary\">Getting issue details...</span></span></p>" + after;
        String export = before + "<p><span class=\"jira-issue\" data-jira-key=\"" + JIRA_KEY + "\">"
                + "<a class=\"jira-issue-key\" href=\"https://jira.example.org/browse/" + JIRA_KEY + "\">"
                + JIRA_KEY + "</a> - <span class=\"summary\">" + JIRA_SENTENCE + "</span></span></p>" + after;
        confluence.page(ATTACHMENTS_PAGE, "Anhänge", SPACE, view).storage(storage).exportView(export)
                .version(1, MODIFIED);
        confluence.attachment("att2003", ATTACHMENTS_PAGE, "symbol.svg", "image/svg+xml", SYMBOL_SVG);
        binary(confluence, "att2004", ATTACHMENTS_PAGE, DOCUMENTS[0][0], DOCUMENTS[0][1], pdf(DOCUMENTS[0][2]));
        binary(confluence, "att2005", ATTACHMENTS_PAGE, DOCUMENTS[1][0], DOCUMENTS[1][1], docx(DOCUMENTS[1][2]));
    }

    /** Ein Anhang mit beliebigen Bytes, wie {@link FakeConfluence#attachment} ohne den Umweg über Text. */
    private static void binary(FakeConfluence confluence, String id, String pageId, String title, String mediaType,
                               byte[] content) {
        FakeConfluence.FakeAttachment attachment = new FakeConfluence.FakeAttachment(id, pageId, title, mediaType,
                content);
        confluence.attachments.put(id, attachment);
        confluence.pages.get(pageId).attachments.add(id);
    }

    /** Ein einseitiges PDF 1.4 mit {@code sentence} als Text (Helvetica), nur aus ASCII-Zeichen. */
    public static byte[] pdf(String sentence) {
        String text = sentence.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        String stream = "BT /F1 12 Tf 72 720 Td (" + text + ") Tj ET";
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R"
                        + " /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        };
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<Integer>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Ein Word-Dokument (OOXML) mit {@code sentence} als einzigem Absatz. */
    public static byte[] docx(String sentence) {
        String text = sentence.replace("&", "&amp;").replace("<", "&lt;");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ZipOutputStream zip = new ZipOutputStream(bytes);
            try {
                entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\""
                        + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/"
                        + "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
                entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
                entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                        + "<w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>");
            } finally {
                zip.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L); // gleiche Bytes bei jedem Lauf
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void page(FakeConfluence confluence, String id, String title, String storage, String view) {
        confluence.page(id, title, SPACE, view).storage(storage).exportView(view).version(1, MODIFIED);
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

    /** Platzhalter des draw.io-Plugins für das Diagramm „ablauf“ der Seite {@code owner}, Revision 1. */
    private static String drawio(String owner) {
        return "<div class=\"drawio-macro\" data-diagramname=\"ablauf\" data-pageid=\"" + owner + "\""
                + " data-revision=\"1\"></div>";
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

    private static String image(String pageId, String file, String attachmentId, String type, String alt,
                                boolean thumbnail) {
        String kind = thumbnail ? "thumbnails" : "attachments";
        String src = "/wiki/download/" + kind + "/" + pageId + "/" + file + "?version=1&amp;api=v2";
        return "<span class=\"confluence-embedded-file-wrapper\"><img class=\"confluence-embedded-image"
                + (thumbnail ? " confluence-thumbnail\" height=\"16\"" : "\"")
                + " alt=\"" + alt + "\" src=\"" + src + "\" data-image-src=\"" + src + "\""
                + " data-linked-resource-id=\"" + attachmentId + "\" data-linked-resource-version=\"1\""
                + " data-linked-resource-type=\"attachment\" data-linked-resource-default-alias=\"" + file + "\""
                + " data-linked-resource-content-type=\"" + type + "\""
                + " data-linked-resource-container-id=\"" + pageId + "\"></span>";
    }

    private static String attachmentLink(String file, String attachmentId, String type) {
        return "<a href=\"/wiki/download/attachments/" + ATTACHMENTS_PAGE + "/" + file + "?version=1&amp;api=v2\""
                + " data-linked-resource-id=\"" + attachmentId + "\" data-linked-resource-type=\"attachment\""
                + " data-linked-resource-content-type=\"" + type + "\""
                + " data-linked-resource-container-id=\"" + ATTACHMENTS_PAGE + "\">" + file + "</a>";
    }
}
