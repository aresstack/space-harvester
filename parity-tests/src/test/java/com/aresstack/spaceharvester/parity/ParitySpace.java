package com.aresstack.spaceharvester.parity;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ParityConfluence;

/**
 * Testseitige Ergänzungen zum Paritäts-Space {@link ParityConfluence} (ADR 0012, docs/PARITY.md, „Vergleichstests“,
 * #193): Adressformen, Dateien einer Quelle ohne eigene Suche und die Suchen darüber. Den Space selbst (Seiten,
 * Merksätze, Anhänge) legt {@link ParityConfluence} aus den Testfixtures des Core an.
 *
 * <p>{@code parity-tests/eac/generate.sh} übersetzt diese Klasse mit {@code --release 8} für den Enterprise AI Client;
 * deshalb nur Java 8.</p>
 */
public final class ParitySpace {

    /**
     * Adressformen, unter denen man eine Seite öffnet (#178): Name und Vorlage; {@code {base}} ist die Basis-URL der
     * Quelle ohne abschließenden Schrägstrich, {@code {loc}} dieselbe ohne Schema (Host, Port, Kontextpfad).
     */
    public static final String[][] ADDRESSES = {
            {"page-id", "{base}/pages/viewpage.action?pageId=1002"},
            {"spaces-pages", "{base}/spaces/PARITY/pages/1002/Architektur"},
            {"display-title", "{base}/display/PARITY/Architektur"},
            {"display-title-encoded", "{base}/display/PARITY/Ebene+4"},
            {"viewpage-title", "{base}/pages/viewpage.action?spaceKey=PARITY&title=Architektur"},
            {"space-home", "{base}/display/PARITY"},
            {"bookmark", "confluence://{loc}/page/1002"},
            {"resource-id", "confluence:{loc}/page/1002"},
            {"unknown-title", "{base}/display/PARITY/Gibt+es+nicht"},
            {"foreign-host", "https://anderes.example.org/wiki/display/PARITY/Architektur"},
    };

    /**
     * Dateien einer Quelle ohne eigene Suche (#180): Name und Inhalt. {@code glossar.md} trägt Akronyme mit und ohne
     * Ausschreibung für den Vergleich der Fakten (#182), dazu die Fälle der Akronymerkennung des EAC.
     */
    public static final String[][] FILES = {
            {"betrieb.md", "# Betrieb\n\nDer Nachtlauf startet um zwei Uhr.\n\nDanach läuft die Sicherung.\n"},
            {"notfall.md", "# Notfall\n\nBei Störungen im Nachtlauf hilft die Rufbereitschaft.\n"},
            {"urlaub.txt", "Urlaubsplanung für das Team im Sommer."},
            {"kantine.txt", "Die Kantine öffnet um elf Uhr."},
            {"glossar.md", "# Glossar\n\nDie Schnittstelle Application Programming Interface (API) liefert Daten.\n\n"
                    + "Das LAN (Local Area Network) verbindet die Rechner.\n\n"
                    + "Die Zentrale Datenverarbeitung (ZDV) prüft das LAN und die API täglich.\n\n"
                    // Gemischte Schreibung, Punkt-Akronym, Kennungen, Code, Adressen, Dateinamen, eine Versalzeile
                    // und zwei Ausschreibungen eines Akronyms (Akronymerkennung wie im EAC, acronyms 4).
                    + "Die Muster GmbH liefert IoT-Sensoren in die U.S.A. und nach Europa.\n\n"
                    + "Die Kennungen MAX_SIZE und ABC-1234 sind keine Akronyme.\n\n"
                    + "Siehe `CSV` und https://example.org/REST, die Datei BERICHT.pdf und info@ZDV.example.org.\n\n"
                    + "WICHTIGER HINWEIS FÜR ALLE NUTZER\n\n"
                    + "Die Projektgruppe (PG) tagt montags. Die PG (Programmiergruppe) tagt freitags.\n"},
    };

    /** Suchen über {@link #FILES} (#180): Name und Suchbegriff. */
    public static final String[][] SEARCHES = {
            {"one-file", "Urlaubsplanung"},
            {"two-files", "Nachtlauf"},
            {"upper-case", "KANTINE"},
            {"none", "Raumstation"},
    };

    /** Suchbegriff der Live-Suche in Confluence und die Seiten, die sie liefert und die beide vorladen (#180). */
    /** Eine Adresse aus {@link #ADDRESSES} für eine Quelle mit dieser Basis-URL. */
    public static String address(String template, String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return template.replace("{base}", base).replace("{loc}", base.substring(base.indexOf("://") + 3));
    }

    private ParitySpace() {
    }
}
