package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.testcorpus.Archives;
import com.aresstack.spaceharvester.testcorpus.Corpus;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HtmlStreamParser} (ADR 0026, AP10): dieselben Ereignisse wie Tikas {@link JSoupParser} an den HTML-Proben,
 * auch an kaputtem HTML, ohne Text aus {@code data:}-Inhalten und Skripten; derselbe Weg für HTML in Archiven und Mails.
 */
class HtmlStreamParserTest {

    /** Die Seite aus den Proben der Anhangsanalyse ({@code samples/seite.html}). */
    static final String SEITE = """
            <!doctype html>
            <html lang="de"><head><meta charset="utf-8"><title>Betriebshandbuch DATENH</title>
            <style>body{font-family:sans-serif}</style><script>var tracking = "nicht für den Leser";</script></head>
            <body><nav><a href="/start">Start</a> | <a href="/hilfe">Hilfe</a></nav>
            <h1>Betriebshandbuch</h1><p>Der <b>Nachtlauf</b> startet um <i>22:00 Uhr</i>, siehe \
            <a href="https://example.org/plan">Ablaufplan</a>.</p>
            <h2>Schritte</h2><ol><li>Sicherung prüfen</li><li>Job <code>DATENH01</code> starten<ul><li>bei Fehler RC 8: \
            Bereitschaft anrufen</li></ul></li></ol>
            <table><tr><th>Job</th><th>Dauer</th></tr><tr><td>DATENH01</td><td>12 min</td></tr><tr><td>DATENH02</td>\
            <td>3 min</td></tr></table>
            <pre>//DATENH01 JOB (ACCT),'NACHTLAUF'</pre></body></html>
            """;

    /** {@code samples/hinweise.html}. */
    static final String HINWEISE = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Hinweise zur Anmeldung"
            + "</title></head><body><h1>Hinweise zur Anmeldung</h1><p>Das Kennwort läuft nach neunzig Tagen ab.</p>"
            + "<ul><li>Zweiter Faktor über die Authentisierungs-App</li><li>Sperre nach fünf Fehlversuchen</li></ul>"
            + "</body></html>";

    /** Verschachtelte Listen und Tabellen, Hüllen ohne Abbildung (div, section, span), lose Texte, offene Absätze. */
    static final String NESTED = """
            <!DOCTYPE html>
            <html lang="de"><head><meta charset="utf-8"><title>Mein Titel</title><meta name="author" content="X">
            <style>p{color:red}</style><script>var a=1;</script></head>
            <body><div class="wrap">Lose <b>fett</b> <h1 id="x">Kopf</h1><p>Absatz mit <a href="http://e.de/x">Link</a>.</p>
            <ul><li>eins<ul><li>innen</li></ul></li><li>zwei</li></ul>
            <table><thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td>1<table><tr><td>tief</td></tr></table></td>
            <td>2</td></tr></tbody></table>
            <pre>code  hier</pre><span>span</span><section><p>in section</p></section><noscript>ns</noscript>
            <dl><dt>T</dt><dd>D</dd></dl><blockquote>Zitat</blockquote><p>offen<p>zweiter</div>Ende</body></html>
            """;

    /** Kaputtes HTML: falsch verschachtelt, verirrter Text in Tabellen, Blöcke in Inline-Elementen, Text nach html. */
    static final String BROKEN = """
            <html><body><b>fett <i>beide</b> nur kursiv</i><p>eins<div>zwei</p>drei</div>
            <table>verirrt<tr><td>Zelle<td>Zwei</tr>mehr<tr><td>X</table>
            <ul><li>a<li>b<p>in b</ul><span><p>span-block</p></span><p>nach span
            <h1>K1<h2>K2</h1>text<a href="x"><p>link-block</p></a><select><option>o1<option>o2</select>
            <frameset><frame src="a"></frameset><p>Ende &amp; &lt;mehr&gt; &nbsp;</body></html>trailing<p>nach html
            """;

    @TempDir
    Path directory;

    @Test
    void theStreamParserComesWithTheJsoupVersionThatTikaBrings() throws Exception {
        // Keine eigene Versionsnummer (AT): fehlt StreamParser in der aufgelösten jsoup, bricht dieser Test.
        assertThat(Class.forName("org.jsoup.parser.StreamParser")).as("jsoup StreamParser").isNotNull();
    }

    @Test
    void tikasHtmlChainStillHasTheClassesAndConstructorsTheStreamUses() throws Exception {
        // Benannte Kopplung (AT): Eine Tika-Anhebung, die eine der beiden Klassen ändert, wird hier rot.
        Class.forName("org.apache.tika.parser.html.HtmlHandler").getConstructor(
                org.apache.tika.parser.html.HtmlMapper.class, org.xml.sax.ContentHandler.class, Metadata.class,
                ParseContext.class, boolean.class);
        Class.forName("org.apache.tika.parser.html.XHTMLDowngradeHandler")
                .getConstructor(org.xml.sax.ContentHandler.class);
        assertThat(HtmlStreamParser.available()).isTrue();
        assertThat(TikaDocumentStructure.config()).contains(HtmlStreamParser.class.getName())
                .contains("parser-exclude class=\"org.apache.tika.parser.html.JSoupParser\"")
                .doesNotContain("HTML_");
        assertThat(TikaDocumentStructure.memoryLimit(ExtractionLimits.DEFAULTS, "text/html; charset=utf-8"))
                .as("mit Strom ohne Schranke").isZero();
    }

    @Test
    void theSamplesGiveExactlyTheEventsOfTikasJsoupParser() throws Exception {
        for (String html : List.of(SEITE, HINWEISE, NESTED, BROKEN)) {
            assertThat(xhtml(new HtmlStreamParser(), html)).isEqualTo(xhtml(new JSoupParser(), html));
        }
    }

    @Test
    void everyMarkTheTikaPathFindsTheStreamFindsToo() throws Exception {
        for (String html : List.of(SEITE, HINWEISE, NESTED, BROKEN)) {
            String tika = text(new JSoupParser(), html);
            String stream = text(new HtmlStreamParser(), html);
            for (String word : tika.split("[^\\p{L}\\p{N}]+")) {
                assertThat(stream).contains(word);
            }
        }
    }

    @Test
    void dataContentAndScriptsGiveNoText() throws Exception {
        String html = "<html><body><p>Vorher</p><a href=\"data:text/plain;base64,R0VIRUlNTkFDSFJJQ0hU\">x</a>"
                + "<img src=\"data:text/plain;charset=utf-8,BILDTEXT\"><script>var s=\"SKRIPTTEXT\";</script>"
                + "<p>Nachher</p></body></html>";
        Path file = Files.writeString(directory.resolve("daten.html"), html);

        TikaDocumentStructureTest.Counting sink = new TikaDocumentStructureTest.Counting("Vorher", "Nachher",
                "BILDTEXT", "SKRIPTTEXT", "GEHEIM");
        StructuredDocument result = new TikaDocumentStructure().extract(file, "daten.html", "text/html",
                ExtractionLimits.DEFAULTS, sink);

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(sink.found).containsExactlyInAnyOrder("Vorher", "Nachher");
        assertThat(sink.entries).as("kein data:-Inhalt als enthaltene Datei").isEmpty();
        // Zum Vergleich: Tikas DOM-Weg liest den data:-Inhalt des Bildes als enthaltene Datei.
        assertThat(xhtml(new JSoupParser(), html)).contains("package-entry");
    }

    @Test
    void htmlInAnArchiveAndAsMailAttachmentTakesTheSamePath() throws Exception {
        // Erkennbar am data:-Inhalt: Der Strom liest ihn nicht, Tikas DOM-Weg schon.
        String html = "<html><body><p>Im Archiv</p><img src=\"data:text/plain;charset=utf-8,BILDTEXT\"></body></html>";
        Path zip = Files.write(directory.resolve("seiten.zip"),
                Archives.zip(Map.of("seite.html", html.getBytes(StandardCharsets.UTF_8))));
        Path page = Files.writeString(directory.resolve("anhang.html"), html);
        Path mail = Corpus.largeAttachmentMail(directory.resolve("post.eml"), page, "anhang.html", "text/html");

        for (Path container : List.of(zip, mail)) {
            TikaDocumentStructureTest.Counting sink = new TikaDocumentStructureTest.Counting("Im Archiv",
                    "BILDTEXT");
            StructuredDocument result = new TikaDocumentStructure().extract(container,
                    container.getFileName().toString(), container == zip ? "application/zip" : "message/rfc822",
                    ExtractionLimits.DEFAULTS, sink);

            assertThat(result.outcome().status()).as("Status %s", result.outcome()).isEqualTo(ExtractionStatus.OK);
            assertThat(sink.found).as(container.getFileName().toString()).containsExactly("Im Archiv");
            assertThat(sink.entries).as("kein data:-Inhalt als enthaltene Datei")
                    .allSatisfy(entry -> assertThat(entry.depth()).isEqualTo(1))
                    .anySatisfy(entry -> assertThat(entry.displayName()).hasValueSatisfying(
                            name -> assertThat(name).endsWith(".html")));
        }
    }

    private static String xhtml(org.apache.tika.parser.Parser parser, String html) throws Exception {
        ToXMLContentHandler handler = new ToXMLContentHandler();
        parser.parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), handler, new Metadata(),
                new ParseContext());
        return handler.toString();
    }

    private static String text(org.apache.tika.parser.Parser parser, String html) throws Exception {
        org.apache.tika.sax.BodyContentHandler handler = new org.apache.tika.sax.BodyContentHandler(-1);
        parser.parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), handler, new Metadata(),
                new ParseContext());
        return handler.toString();
    }
}
