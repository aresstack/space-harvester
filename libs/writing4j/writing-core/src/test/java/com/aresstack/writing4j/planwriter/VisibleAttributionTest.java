package com.aresstack.writing4j.planwriter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The rule that a sentence naming the speaker of a claim does not state it as a fact; self-written sentences. */
public class VisibleAttributionTest {

    private static final List<String> NONE = Collections.emptyList();
    private static final String MAKER = "HERSTELLER_HANDEL";
    private static final String ASSOCIATION = "VERBAND";

    private static boolean named(String sentence, int number) {
        return VisibleAttribution.attributes(sentence, number, NONE);
    }

    private static boolean named(String sentence, int number, String sourceKind, String... names) {
        return VisibleAttribution.attributes(sentence, number, Arrays.asList(names), sourceKind);
    }

    private static boolean named(String sentence, int number, String sourceKind, Collection<String> names) {
        return VisibleAttribution.attributes(sentence, number, names, sourceKind);
    }

    private static String withSpeaker(String sentence, int number, String phrase) {
        return VisibleAttribution.withSpeaker(sentence, number, NONE, phrase);
    }

    private static String withSpeaker(String sentence, int number, String phrase, Integer... own) {
        return VisibleAttribution.withSpeaker(sentence, number, NONE, phrase, Arrays.asList(own));
    }

    @Test
    public void everyFrameThatHandsAClaimToItsSpeakerCounts() {
        assertTrue(named("Nach Angaben des Herstellers erkennt die Kamera Kennzeichen auch bei Nacht [2].", 2));
        assertTrue(named("Laut einem Anbieter senkt das System den Verbrauch um zehn Prozent [1].", 1));
        assertTrue(named("Laut einem großen deutschen Anbieter senkt das System den Verbrauch [1].", 1));
        assertTrue(named("Dem Verband zufolge fehlen in vielen Städten sichere Radwege [3].", 3));
        assertTrue(named("Die Ampel schaltet nach 40 Sekunden um, so der Hersteller [1].", 1));
        assertTrue(named("Ein großer Anbieter beschreibt, dass die Sensoren auch Regen erkennen [2].", 2));
        assertTrue(named("Wie der Verband betont, sinken die Unfallzahlen seit Jahren [4].", 4));
        assertTrue(named("Nach Herstellerangaben hält der Akku acht Stunden [1].", 1));
        assertTrue(named("Der Hersteller gibt eine Reichweite von 300 Kilometern an [2].", 2));
        assertTrue("a compound holds the speaker", named("Laut Softwareanbietern lässt sich die Anlage per App "
                + "steuern [1].", 1));
        assertTrue(named("Laut Branchenportalen steigt die Nachfrage nach Kameras [1].", 1));
        assertTrue(named("Betont wird vom Verband nichts, doch empfiehlt ein Verband mehr Zonen [1].", 1));
        assertTrue("its own account, and the speaker is in the sentence", named("Nach eigenen Angaben liefert der "
                + "Hersteller die Anlage in zwei Wochen [1].", 1));
        assertTrue(named("According to the manufacturer, the camera reads plates at night [1].", 1));
        assertTrue(named("The camera reads every plate, as a vendor states [1].", 1));

        assertTrue(named("Laut einem Sprecher des Herstellers erkennt die Kamera jedes Kennzeichen [1].", 1, MAKER));
        assertTrue(named("Laut der Website des Herstellers erkennt die Kamera jedes Kennzeichen [1].", 1, MAKER));
        assertTrue(named("Laut dem Datenblatt des Herstellers erreicht die Kamera 120 Bilder pro Sekunde [1].", 1,
                MAKER));
        assertTrue(named("Nach Angaben eines Sprechers des Verbands sinken die Unfallzahlen [1].", 1, ASSOCIATION));
        assertTrue(named("Laut einer Studie des Verbands sinken die Unfallzahlen [1].", 1, ASSOCIATION));
        assertTrue(named("Aus Sicht des Verbands fehlen sichere Radwege [1].", 1, ASSOCIATION));
        assertTrue(named("Die Kamera erkennt jedes Kennzeichen, heißt es beim Hersteller [1].", 1, MAKER));
        assertTrue(named("Die Kamera erkennt jedes Kennzeichen, so ein Sprecher des Herstellers [1].", 1, MAKER));
        assertTrue(named("In seinem Jahresbericht betont der Verband, dass die Unfallzahlen sinken [1].", 1,
                ASSOCIATION));
        assertTrue(named("Der Verband empfiehlt mehr Tempo-30-Zonen in Wohngebieten [1].", 1, ASSOCIATION));
        assertTrue(named("Der Verband weist darauf hin, dass viele Radwege zu schmal sind [1].", 1, ASSOCIATION));
        assertTrue(named("Dabei gibt der Hersteller an, dass die Kamera jedes Kennzeichen erkennt [1].", 1, MAKER));
        assertTrue(named("Der Hersteller liefert nach eigenen Angaben die Anlage in zwei Wochen [1].", 1, MAKER));
        assertTrue(named("Die Kamera ist laut Hersteller wetterfest [1].", 1, MAKER));
        assertTrue(named("The vendor says that the camera reads every plate [1].", 1, MAKER));
        assertTrue(named("According to the company's website, the camera reads every plate [1].", 1, MAKER));
        assertTrue(named("The camera reads every plate, says the company [1].", 1, MAKER));
    }

    @Test
    public void aSpeakerOutsideAFrameOrAnotherKindOfSourceNamesNoSpeakerOfTheClaim() {
        assertFalse(named("Die Kamera erkennt jedes Kennzeichen zuverlässig [2].", 2));
        assertFalse("a study speaks, the companies only save", named("Laut einer Studie sparen "
                + "Transportunternehmen Kraftstoff [1].", 1));
        assertFalse(named("Der Hersteller hat seinen Sitz in Hamburg, und die Kamera erkennt Fahrzeuge [1].", 1));
        assertFalse("the rule knows makers, sellers and associations only", named("Laut Polizei sind die Unfälle "
                + "gesunken [1].", 1));
        assertFalse(named("Laut Best-Practice-Beispielen sinken die Kosten deutlich [1].", 1));
        assertFalse(named("Laut internationalen Marktanalysen wächst der Markt [1].", 1));
        assertFalse("its own account without a speaker", named("Nach eigenen Angaben sinken die Kosten [1].", 1));
        assertFalse(named("Nach eigenen Angaben will die Stadt mehr gegen Raser tun, denn die Kameras erkennen jedes "
                + "Kennzeichen [1].", 1, MAKER));
        assertFalse(named("", 1));
        assertFalse(named(null, 1));
    }

    /** The review of 22.11.2026: a maker, a seller or an association as object, owner or customer says nothing. */
    @Test
    public void aSpeakerWordThatIsNoSpeakerOfTheClaimClearsNothing() {
        assertFalse("an object", named("Die Software empfiehlt dem Händler automatisch den besten Preis [1].", 1,
                MAKER));
        assertFalse(named("Die Plattform empfiehlt den Händlern passende Produkte [1].", 1, MAKER));
        assertFalse(named("Das System empfiehlt dem Unternehmen, Routen zu optimieren [1].", 1, MAKER));
        assertFalse(named("Die Kamera warnt den Händler, sobald ein Fahrzeug die Schranke passiert [1].", 1, MAKER));
        assertFalse(named("Die Plattform präsentiert den Unternehmen passende Bewerber [1].", 1, MAKER));
        assertFalse(named("Das System warnt den Hersteller frühzeitig vor Ausfällen [1].", 1, MAKER));
        assertFalse("customers who like it", named("Besonders schätzen die Unternehmen die einfache Bedienung der "
                + "Kamera [1].", 1, MAKER));
        assertFalse(named("Viele Händler schätzen die Kamera, weil sie jedes Kennzeichen erkennt [1].", 1, MAKER));
        assertFalse(named("Die Kamera, die viele Unternehmen schätzen, erkennt jedes Kennzeichen [1].", 1, MAKER));
        assertFalse("an owner", named("Die Kamera des Herstellers warnt den Fahrer bei Müdigkeit [1].", 1, MAKER));
        assertFalse(named("Die App des Anbieters rät dem Fahrer nach zwei Stunden zu einer Pause [1].", 1, MAKER));
        assertFalse(named("Die Software des Unternehmens schätzt den Verkehrsfluss in Echtzeit [1].", 1, MAKER));
        assertFalse(named("In monatlichen Raten der Anbieter kostet die Kamera 30 Euro [1].", 1, MAKER));
        assertFalse("a maker that shows, not says", named("Der Hersteller präsentiert eine Kamera, die jedes "
                + "Kennzeichen auch bei Nacht erkennt [1].", 1, MAKER));
        assertFalse(named("Für Logistikunternehmen empfiehlt sich ein System, das den Verbrauch senkt [1].", 1,
                MAKER));
        assertFalse("the adjective laut", named("Die Wärmepumpe ist kaum laut und spart Unternehmen viel Strom [1].",
                1, MAKER));
        assertFalse("a contrast turns the speaker against the claim", named("Anders als die Hersteller behaupten, "
                + "erkennt die Kamera nur jedes zweite Kennzeichen [1].", 1));
        assertFalse("a noun is no verb of saying", named("Entgegen den Versprechen der Hersteller erkennt die Kamera "
                + "nur jedes zweite Kennzeichen [1].", 1));
        assertFalse(named("The system warns the retailer when stock runs low [1].", 1, MAKER));
        assertFalse(named("The tool recommends the best supplier for each order [1].", 1, MAKER));
        assertFalse(named("The system estimates the company's savings at 20 percent [1].", 1, MAKER));
        assertFalse(named("The camera reads every plate at night, so the company needs no extra lighting [1].", 1,
                MAKER));
        assertFalse(named("The sensor needs no maintenance, so its developers offer a ten-year warranty [1].", 1,
                MAKER));
        assertFalse("a speaker word inside another word", named("According to lawmakers, the camera halves the "
                + "number of accidents [1].", 1, MAKER));
        assertFalse(named("According to the accompanying study, the camera halves the number of accidents [1].", 1,
                MAKER));
        assertFalse(named("According to experts many companies save fuel [1].", 1, MAKER));
        assertFalse(named("Laut der Energieberatung der Stadt spart eine Wärmepumpe viel Heizkosten [1].", 1, MAKER));
    }

    @Test
    public void aSpeakerOfAnotherClauseNamesNoSpeakerOfTheClaim() {
        assertFalse(named("Der Hersteller nennt keinen Preis, doch die Kamera erkennt jedes Kennzeichen [1].", 1,
                MAKER));
        assertFalse(named("Kein Hersteller gibt an, wie oft die Kamera ausfällt; sie erkennt jedes Kennzeichen [1].",
                1, MAKER));
        assertFalse(named("Der Hersteller präsentiert die Kamera auf einer Messe; sie erkennt jedes Kennzeichen [1].",
                1, MAKER));
        assertFalse(named("Während der Verband strengere Regeln empfiehlt, erkennt die Kamera bereits jedes "
                + "Fahrzeug [1].", 1, ASSOCIATION));
        assertFalse(named("The manufacturer rejected all warranty claims, and the camera reads every plate [1].", 1,
                MAKER));
        assertFalse("after the last marker only a tag counts", named("Die Kamera erkennt jedes Kennzeichen [1], und "
                + "der Hersteller nennt einen Preis von 5.000 Euro.", 1, MAKER));
        String tagged = "Die Kamera erkennt jedes Kennzeichen [1], so der Hersteller, und sie misst die "
                + "Geschwindigkeit genau [2].";
        assertTrue("a tag belongs to the marker before it", named(tagged, 1, MAKER));
        assertFalse(named(tagged, 2, MAKER));
        assertFalse("a semicolon ends a frame's reach", named("Laut einem Verband fehlen in vielen Städten Radwege "
                + "[1]; die Kamera erkennt jedes Fahrrad [2].", 2, ASSOCIATION));
        assertFalse("a frame before a semicolon speaks for its own clause only", named("Der Verband betont: Radwege "
                + "fehlen; die Kamera erkennt jedes Fahrrad [1].", 1, ASSOCIATION));
        assertFalse(named("Laut dem Hersteller ist die Kamera wetterfest [1], tatsächlich erkennt sie aber nur jedes "
                + "zweite Kennzeichen [2].", 2, MAKER));
    }

    @Test
    public void theSpeakerMustBeOfTheSourcesKind() {
        String association = "Laut einem Verband erkennt die Kamera jedes Kennzeichen [2].";
        assertFalse("an association speaks for a maker's passage", named(association, 2, MAKER));
        assertTrue(named(association, 2, ASSOCIATION));
        assertTrue("any speaker for a source of no such kind", named(association, 2, ""));
        String vendor = "Die Kamera erkennt jedes Kennzeichen, wie ein Anbieter angibt [2].";
        assertTrue(named(vendor, 2, MAKER));
        assertFalse(named(vendor, 2, ASSOCIATION));
        assertTrue("the industry speaks for both", named("Laut Branchenkennern steigt die Nachfrage [1].", 1, MAKER));
        assertTrue(named("Laut Branchenkennern steigt die Nachfrage [1].", 1, ASSOCIATION));
        String oneGroup = "Mehr Kontrollen machen Tempo-30-Zonen sicherer, wie ein Anbieter angibt [2, 3].";
        assertTrue(named(oneGroup, 2, MAKER));
        assertFalse("one group, two kinds of source", named(oneGroup, 3, ASSOCIATION));
    }

    @Test
    public void eachClaimNeedsItsOwnPartUnlessOneFrameOpensTheSentence() {
        String opened = "Nach Angaben des Herstellers erkennt das System Fußgänger [1] und Radfahrer [2].";
        assertTrue(named(opened, 1));
        assertTrue("the opening frame carries every claim", named(opened, 2));
        assertTrue(named("Laut Hersteller erkennt das System Fußgänger [1], Radfahrer [2] und Tiere [3].", 3, MAKER));
        assertTrue(named("Laut Hersteller gilt: Die Kamera erkennt jedes Kennzeichen [1].", 1, MAKER));
        String mixed = "Die Stadt misst weniger Lärm [1], während ein Anbieter beschreibt, dass die Kamera jedes "
                + "Fahrzeug erkennt [2].";
        assertFalse(named(mixed, 1));
        assertTrue(named(mixed, 2));
        String late = "Die Stadt misst weniger Lärm [1], und die Kamera erkennt jedes Fahrzeug [2].";
        assertFalse(named(late, 2));
        assertTrue("the last part takes a tag after its marker", named("Die Kamera erkennt jedes Fahrzeug [3], so der "
                + "Hersteller.", 3));
        assertTrue("one group, two passages", named("Laut einem Anbieter erkennt die Kamera Fahrzeuge [2, 3].", 3));
        assertTrue("no marker cites it: the whole sentence", named("Laut einem Anbieter erkennt die Kamera "
                + "Fahrzeuge [2].", 5));
        assertFalse(named("Die Kamera erkennt Fahrzeuge [2].", 5));
        assertFalse("two frames: none opens the sentence alone", named("Nach Angaben des Herstellers erkennt das "
                + "System Fußgänger [1] und Radfahrer [2], wie ein Verband betont [3].", 2));
        assertFalse("a new clause with its own subject", named("Laut Hersteller erkennt die Kamera Fußgänger [1] und "
                + "die Ampel schaltet um [2].", 2, MAKER));
        assertFalse("a frame of another clause opens nothing", named("Der Hersteller nennt keinen Preis, doch das "
                + "System erkennt Fußgänger [1] und Radfahrer [2].", 2, MAKER));
        assertFalse("more than three words before the frame", named("Im vergangenen Jahr auf der Messe in Köln laut "
                + "Hersteller erkannte die Kamera Fußgänger [1] und Radfahrer [2].", 2, MAKER));
    }

    @Test
    public void theSourcesSiteIsANameOfItsSpeaker() {
        Collection<String> names = VisibleAttribution.namesOf("https://www.verkehrstechnik-nord.example/kameras");
        assertEquals(Arrays.asList("verkehrstechnik-nord.example", "verkehrstechnik-nord"), names);
        assertTrue(VisibleAttribution.attributes("Laut verkehrstechnik-nord.example blockiert die Schranke bei Stau "
                + "[1].", 1, names));
        assertTrue(VisibleAttribution.attributes("Verkehrstechnik-Nord beschreibt, dass die Schranke bei Stau "
                + "öffnet [1].", 1, names));
        assertFalse("without the name it is no speaker", named("Laut verkehrstechnik-nord.example blockiert die "
                + "Schranke bei Stau [1].", 1));
        assertTrue(named("Laut der Radwegeunion sinken die Unfallzahlen [1].", 1, ASSOCIATION,
                VisibleAttribution.namesOf("radwegeunion.example")));

        Collection<String> blitzer = VisibleAttribution.namesOf("blitzer.example");
        assertEquals(Arrays.asList("blitzer.example", "blitzer"), blitzer);
        assertFalse("a name outside a frame", named("Die Blitzer warnen Autofahrer rechtzeitig vor Gefahren [2].", 2,
                MAKER, blitzer));
        assertFalse(named("Die Blitzer schätzen die Geschwindigkeit auf einen km/h genau [2].", 2, MAKER, blitzer));
        assertFalse("a name in a compound", named("Blitzer-Apps warnen Autofahrer vor jeder Kontrolle [1].", 1, MAKER,
                blitzer));
        assertFalse(named("Die Optika-Kameras melden Stau und warnen die Leitstelle [1].", 1, MAKER,
                VisibleAttribution.namesOf("optika.example")));
        Collection<String> sub = VisibleAttribution.namesOf("https://source.sturzwacht.example/app");
        assertEquals("the name before the public suffix, not a subdomain",
                Arrays.asList("source.sturzwacht.example", "sturzwacht"), sub);
        assertFalse(named("According to one source, the app detects every fall [1].", 1, MAKER, sub));
        assertFalse("a name inside a word", named("Die App schont Ressourcen und warnt den Träger bei einem Sturz "
                + "[1].", 1, MAKER, sub));
        assertFalse(named("Laut Fachblogs öffnet das Schloss in unter einer Sekunde [1].", 1, MAKER,
                VisibleAttribution.namesOf("blog.schlosswerk.example")));
        assertEquals(Arrays.asList("radwege-nord.co.uk", "radwege-nord"),
                VisibleAttribution.namesOf("https://www.radwege-nord.co.uk/news"));

        assertEquals("example.com", VisibleAttribution.hostOf("https://www.Example.com/a/b?x=1"));
        assertEquals("example.com", VisibleAttribution.hostOf("example.com/x"));
        assertEquals("shop.example.de", VisibleAttribution.hostOf("http://nutzer@shop.example.de:8080/p"));
        assertEquals("a password before the host", "example.com",
                VisibleAttribution.hostOf("https://nutzer:kennwort@example.com/a"));
        assertEquals("an @ in the path", "example.com",
                VisibleAttribution.hostOf("https://example.com/weiter@andere.example"));
        assertEquals("no scheme", "cdn.vendor.example", VisibleAttribution.hostOf("//cdn.vendor.example/x"));
        assertEquals("münchen.example", VisibleAttribution.hostOf("münchen.example"));
        assertEquals("an id is no site", "", VisibleAttribution.hostOf("blitzer"));
        assertEquals("", VisibleAttribution.hostOf("src-1234"));
        assertEquals("", VisibleAttribution.hostOf(""));
        assertEquals("", VisibleAttribution.hostOf(null));
        assertEquals("a short name counts only in capitals", Arrays.asList("abc.example", "ABC"),
                VisibleAttribution.namesOf("abc.example"));
        Collection<String> vdb = VisibleAttribution.namesOf("portal.vdb.example");
        assertEquals(Arrays.asList("portal.vdb.example", "VDB"), vdb);
        assertTrue(named("Laut dem VDB sinken die Unfallzahlen [1].", 1, ASSOCIATION, vdb));
        assertTrue(named("Der VDB empfiehlt mehr Tempo-30-Zonen [1].", 1, ASSOCIATION, vdb));
        assertFalse("a short word in lower case names no source", named("Laut vdb sinken die Unfallzahlen [1].", 1,
                ASSOCIATION, vdb));
        Collection<String> app = VisibleAttribution.namesOf("app.example");
        assertEquals(Arrays.asList("app.example", "APP"), app);
        assertFalse("a short word names no source", named("Laut App sinken die Unfallzahlen [1].", 1, ASSOCIATION,
                app));
        assertTrue(named("Laut APP sinken die Unfallzahlen [1].", 1, ASSOCIATION, app));
        assertEquals(Collections.singletonList("a-b.example"), VisibleAttribution.namesOf("a-b.example"));
        assertEquals(Arrays.asList("laermschutz.europa.eu", "laermschutz"),
                VisibleAttribution.namesOf("https://laermschutz.europa.eu/de"));
        assertTrue(VisibleAttribution.namesOf("blitzer").isEmpty());
    }

    /** The review of c10b: 54 sentences named a domain as the speaker ("Der Hersteller conrad.at"). */
    @Test
    public void theWriterNamesASiteByItsOwnNameWithoutItsEnding() {
        assertEquals("Verkehrstechnik-Nord", VisibleAttribution.nameOf("https://www.verkehrstechnik-nord.example/k"));
        assertEquals("Sturzwacht", VisibleAttribution.nameOf("https://source.sturzwacht.example/app"));
        assertEquals("Radwege-Nord", VisibleAttribution.nameOf("https://www.radwege-nord.co.uk/news"));
        assertEquals("up to four letters in capitals", "VDB", VisibleAttribution.nameOf("portal.vdb.example"));
        assertEquals("KFZV", VisibleAttribution.nameOf("kfzv.example"));
        assertEquals("Blitzer", VisibleAttribution.nameOf("blitzer.example"));
        assertEquals("München", VisibleAttribution.nameOf("münchen.example"));
        assertEquals("a site that hosts others: the name under it", "Laermschutz",
                VisibleAttribution.nameOf("https://laermschutz.europa.eu/de"));
        assertEquals("Verkehrsamt", VisibleAttribution.nameOf("verkehrsamt.bund.de"));
        assertEquals("no name of its own: the host", "a-b.example", VisibleAttribution.nameOf("a-b.example"));
        assertEquals("x.example", VisibleAttribution.nameOf("x.example"));
        assertEquals("decoded from punycode", "München", VisibleAttribution.nameOf("xn--mnchen-3ya.example"));
        assertEquals("no site", "", VisibleAttribution.nameOf("blitzer"));
        assertEquals("", VisibleAttribution.nameOf(null));

        for (String url : new String[]{"https://www.verkehrstechnik-nord.example/k", "portal.vdb.example",
                "kfzv.example", "münchen.example", "a-b.example", "radwegeunion.example"}) {
            String name = VisibleAttribution.nameOf(url);
            assertTrue("the name the writer is given names the source: " + name, named("Laut " + name + " sinken "
                    + "die Unfallzahlen [1].", 1, ASSOCIATION, VisibleAttribution.namesOf(url)));
        }
    }

    @Test
    public void theSpeakerIsPutInBeforeTheMarkerOfTheClaim() {
        String phrase = ", wie ein Anbieter angibt";
        assertEquals("Die Kamera erkennt jedes Kennzeichen, wie ein Anbieter angibt [2].",
                withSpeaker("Die Kamera erkennt jedes Kennzeichen [2].", 2, phrase));
        String middle = withSpeaker("Die Kamera erkennt Fußgänger [3] und warnt den Fahrer [4].", 3, phrase,
                3, 4);
        assertEquals("a comma closes it when the sentence goes on", "Die Kamera erkennt Fußgänger, wie ein Anbieter "
                + "angibt [3], und warnt den Fahrer [4].", middle);
        assertTrue(named(middle, 3));
        assertFalse(named(middle, 4));
        assertEquals("Die Kamera erkennt Fußgänger, wie ein Anbieter angibt [3], und sie warnt [4].",
                withSpeaker("Die Kamera erkennt Fußgänger [3], und sie warnt [4].", 3, phrase, 3, 4));
        String named = "Laut einem Anbieter erkennt die Kamera Fußgänger [3].";
        assertEquals(named, withSpeaker(named, 3, phrase));
        assertEquals("not cited", "Die Kamera erkennt Fußgänger [3].",
                withSpeaker("Die Kamera erkennt Fußgänger [3].", 4, phrase));
        assertEquals("no words before the marker", "[3] Die Kamera erkennt Fußgänger.",
                withSpeaker("[3] Die Kamera erkennt Fußgänger.", 3, phrase));
        String english = withSpeaker("The camera reads every plate [1].", 1, ", as a vendor states");
        assertEquals("The camera reads every plate, as a vendor states [1].", english);
        assertTrue(named(english, 1));
        assertTrue(named(withSpeaker("Die Anlage spart Strom [5].", 5, ", wie ein Verband angibt"), 5,
                ASSOCIATION));
    }

    /** The review of 22.11.2026: "… zuverlässig., wie ein Anbieter angibt [2]" fused two sentences. */
    @Test
    public void theSpeakerGoesWhereItReadsAsPartOfTheSentenceOrNowhere() {
        String phrase = ", wie ein Anbieter angibt";
        assertEquals("before the full stop when the marker follows it", "Die Kamera erkennt jedes Kennzeichen, wie "
                + "ein Anbieter angibt. [2]", withSpeaker("Die Kamera erkennt jedes Kennzeichen. [2]", 2,
                phrase));
        assertEquals("Erkennt die Kamera jedes Kennzeichen, wie ein Anbieter angibt? [2]",
                withSpeaker("Erkennt die Kamera jedes Kennzeichen? [2]", 2, phrase));
        assertEquals("after a number and its unit", "Die Anlage senkt den Verbrauch um 10 %, wie ein Anbieter angibt "
                + "[1].", withSpeaker("Die Anlage senkt den Verbrauch um 10 % [1].", 1, phrase));
        String comma = "Die Kamera erkennt jedes Kennzeichen, [2] auch bei Nacht.";
        assertEquals("no place after a comma", comma, withSpeaker(comma, 2, phrase));
        String quote = "Sie nennt die Kamera „sicher.“ [2]";
        assertEquals("no place after a quote", quote, withSpeaker(quote, 2, phrase));
        String semicolon = "Die Kamera erkennt jedes Kennzeichen; [2] die Stadt misst.";
        assertEquals(semicolon, withSpeaker(semicolon, 2, phrase));
    }

    @Test
    public void noSecondSpeakerIsPutInWhereOneAlreadySpeaks() {
        String phrase = ", wie ein Anbieter angibt";
        String opened = "Nach Angaben des Herstellers erkennt das System Fußgänger [1] und Radfahrer [2].";
        assertEquals("the opening frame carries the claim", opened, withSpeaker(opened, 2, phrase));
        String other = "Laut einem Verband erkennt die Kamera jedes Kennzeichen [2].";
        assertEquals("a speaker of another kind stays for the check", other, withSpeaker(other, 2, phrase));
        String otherOpens = "Laut einem Verband erkennt die Kamera Fußgänger [1] und Radfahrer [2].";
        assertEquals(otherOpens, withSpeaker(otherOpens, 2, phrase));
        assertEquals(other, withSpeaker(other, 2, ", wie ein Verband angibt"));
        String carried = "Laut Hersteller erkennt das System Fußgänger [1] und Radfahrer [2], und die Ampel schaltet "
                + "um [2].";
        assertEquals("no second speaker in a sentence that names one", carried, withSpeaker(carried, 2,
                phrase, 1, 2));
    }

    /** The review of c10b: ", wie ein Anbieter angibt" beside "laut einer Quelle". */
    @Test
    public void noSpeakerIsPutInBesideAnyOtherAttribution() {
        String phrase = ", wie ein Anbieter angibt";
        for (String sentence : new String[]{
                "Laut einer Quelle erkennt die Kamera jedes Fahrzeug [2].",
                "Die Kamera erkennt laut Experten jedes Fahrzeug [2].",
                "Laut sturzwacht.example erkennt die App jeden Sturz [2].",
                "Einer Studie zufolge erkennt die Kamera jedes Fahrzeug [2].",
                "Nach eigenen Angaben erkennt die Kamera jedes Fahrzeug [2].",
                "Aus Sicht der Forscher erkennt die Kamera jedes Fahrzeug [2].",
                "Die Kamera erkennt jedes Fahrzeug, heißt es in der Studie [2].",
                "Die Kamera erkennt jedes Fahrzeug, wie Tests zeigen [2].",
                "Die Kamera erkennt jedes Fahrzeug, so die Autoren [2].",
                "Fachleute betonen, dass die Kamera jedes Fahrzeug erkennt [2].",
                "Der Hersteller verspricht acht Stunden Laufzeit [2].",
                "Der Hersteller gibt eine Laufzeit von acht Stunden an [2].",
                "Messungen belegen, dass die Kamera jedes Fahrzeug erkennt [2].",
                "Angeblich erkennt die Kamera jedes Fahrzeug [2].",
                "According to one study, the camera reads every plate [2].",
                "The camera reportedly reads every plate [2].",
                "As researchers report, the camera reads every plate [2].",
                "Studies show that the camera reads every plate [2]."}) {
            assertTrue(sentence, VisibleAttribution.reported(sentence));
            assertEquals(sentence, sentence, withSpeaker(sentence, 2, phrase));
        }
        for (String sentence : new String[]{
                "Die Kamera erkennt jedes Fahrzeug [2].",
                "Der Lüfter der Kamera ist laut [2].",
                "Laut und deutlich warnt die Sirene den Fahrer [2].",
                "Die Anzeige zeigt die Geschwindigkeit, die Uhrzeit und das Datum [2].",
                "Die Kamera gibt ein Signal an die Leitstelle [2].",
                "Tempo 30 senkt den Lärm, so dass Anwohner besser schlafen [2].",
                "Demzufolge sinkt der Lärm in den Wohnstraßen [2].",
                "The camera reads every plate [2]."}) {
            assertFalse(sentence, VisibleAttribution.reported(sentence));
        }
        assertEquals("Die Anzeige zeigt die Geschwindigkeit, die Uhrzeit und das Datum, wie ein Anbieter angibt [2].",
                withSpeaker("Die Anzeige zeigt die Geschwindigkeit, die Uhrzeit und das Datum [2].", 2,
                        phrase));
        assertFalse(VisibleAttribution.reported(null));
    }

    /** The review of c10b: a sentence with "[15] …, wie ein Anbieter angibt [12]" reads as the speaker of both. */
    @Test
    public void aSpeakerGoesOnlyIntoAClauseThatCitesNoOtherSourceAndOnlyOnce() {
        String phrase = ", wie ein Anbieter angibt";
        String comma = "Die Stadt misst den Lärm [1], und die Kamera erkennt jedes Fahrzeug [2].";
        assertEquals("another source in the clause", comma, withSpeaker(comma, 2, phrase));
        assertEquals("Die Stadt misst den Lärm [1]; die Kamera erkennt jedes Fahrzeug, wie ein Anbieter angibt [2].",
                withSpeaker("Die Stadt misst den Lärm [1]; die Kamera erkennt jedes Fahrzeug [2].", 2, phrase));
        String after = "Die Kamera erkennt jedes Fahrzeug [2] und die Stadt misst den Lärm [1].";
        assertEquals("another source after the group", after, withSpeaker(after, 2, phrase));
        String bracket = "Die Kamera erkennt jedes Fahrzeug [1; 2].";
        assertEquals("a \";\" inside a group splits no clause", bracket, withSpeaker(bracket, 2, phrase));
        assertEquals("Die Kamera erkennt jedes Fahrzeug, wie ein Anbieter angibt [1; 2].", withSpeaker(bracket, 2,
                phrase, 1, 2));
        String before = "Die Kamera erkennt jedes Fahrzeug [1; 3] und warnt den Fahrer [2].";
        assertEquals("nor a \";\" inside the group before", before, withSpeaker(before, 2, phrase));
        assertEquals("the same source", "Die Kamera erkennt jedes Fahrzeug, wie ein Anbieter angibt [2], und warnt "
                + "den Fahrer [5].", withSpeaker("Die Kamera erkennt jedes Fahrzeug [2] und warnt den Fahrer [5].", 2,
                phrase, 2, 5));
        String twice = "Die Kamera erkennt Fußgänger [2] und warnt den Fahrer [2].";
        assertEquals("one phrase per sentence", twice, withSpeaker(twice, 2, phrase));
    }

    /** The review of 22.11.2026: no speaker beside a speaker word or a name of the source, wherever it stands. */
    @Test
    public void noSpeakerIsPutInWhereTheSentenceNamesASpeakerWordOrTheSource() {
        String phrase = ", wie ein Anbieter angibt";
        for (String sentence : new String[]{
                "Der Hersteller präsentiert eine Kamera, die jedes Kennzeichen erkennt [2].",
                "Die Kamera des Verbands erkennt jedes Kennzeichen [2].",
                "Auf der Herstellerseite steht ein Preis von 500 Euro [2].",
                "Branchenkenner erwarten mehr Kameras in den Städten [2].",
                "The vendor's camera reads every plate [2]."}) {
            assertEquals(sentence, sentence, withSpeaker(sentence, 2, phrase));
        }
        String common = "Viele Unternehmen setzen die Kamera bereits ein [2].";
        assertEquals("the cost: a speaker word that names no speaker keeps it out too", common,
                withSpeaker(common, 2, phrase));
        Collection<String> sturzwacht = VisibleAttribution.namesOf("https://source.sturzwacht.example/app");
        String app = "Die Sturzwacht-App erkennt jeden Sturz [2].";
        assertEquals("a name of the source", app, VisibleAttribution.withSpeaker(app, 2, sturzwacht, phrase));
        String genitive = "Sturzwachts App erkennt jeden Sturz [2].";
        assertEquals(genitive, VisibleAttribution.withSpeaker(genitive, 2, sturzwacht, phrase));
        assertEquals("no name given, nothing named", "Die Sturzwacht-App erkennt jeden Sturz, wie ein Anbieter angibt "
                + "[2].", withSpeaker(app, 2, phrase));
        Collection<String> blitzer = VisibleAttribution.namesOf("blitzer.example");
        assertEquals("a name inside a word names nothing", "Die Blitzersäule misst jedes Fahrzeug, wie ein Anbieter "
                + "angibt [2].", VisibleAttribution.withSpeaker("Die Blitzersäule misst jedes Fahrzeug [2].", 2,
                blitzer, phrase));
        Collection<String> vdb = VisibleAttribution.namesOf("portal.vdb.example");
        String campaign = "Die VDB-Kampagne senkt die Unfallzahlen [1].";
        assertEquals(campaign, VisibleAttribution.withSpeaker(campaign, 1, vdb, ", wie ein Verband angibt"));
        assertEquals("a short name only in capitals", "Die vdb-Kampagne senkt die Unfallzahlen, wie ein Verband "
                + "angibt [1].", VisibleAttribution.withSpeaker("Die vdb-Kampagne senkt die Unfallzahlen [1].", 1,
                vdb, ", wie ein Verband angibt"));
    }

    /** The review of 22.11.2026: more constructions that report what anyone says. */
    @Test
    public void moreReportingConstructionsKeepTheSpeakerOut() {
        String phrase = ", wie ein Anbieter angibt";
        for (String sentence : new String[]{
                "Lt. Hersteller erkennt die Kamera jedes Kennzeichen [2].",
                "Gem. Datenblatt hält der Akku acht Stunden [2].",
                "Gemäß „Auto heute“ erkennt die Kamera jedes Kennzeichen [2].",
                "Laut 2023 veröffentlichten Zahlen sinken die Unfälle [2].",
                "Nach einer Studie erkennt die Kamera jedes Kennzeichen [2].",
                "Nach neuen Medienberichten sinken die Unfallzahlen [2].",
                "Einer Quelle nach erkennt die Kamera jedes Kennzeichen [2].",
                "Die Daten zeigen einen Rückgang der Unfälle [2].",
                "Von 300 Unfällen im Jahr ist in dem Bericht die Rede [2].",
                "Die Kamera erkennt jedes Kennzeichen, wie aus einer Studie hervorgeht [2].",
                "Die Kamera erkennt jedes Kennzeichen, wie im Datenblatt steht [2].",
                "Sturzwacht beziffert die Fehlalarme auf zwei Prozent [2].",
                "Der Verband warnt vor zu schmalen Radwegen [2].",
                "Die Polizei meldet weniger Unfälle in den Tempo-30-Zonen [2].",
                "Dem Datenblatt ist eine Laufzeit von acht Stunden zu entnehmen [2].",
                "Forscher fanden heraus, dass die Kamera jedes Kennzeichen erkennt [2].",
                "Der Hersteller teilt auf seiner Website eine Laufzeit von acht Stunden mit [2].",
                "Die Stadt stellt mehr Kontrollen in Aussicht [2].",
                "Der Verband spricht sich für mehr Kontrollen aus [2].",
                "Der Verband setzt sich für breitere Radwege ein [2].",
                "Die Stadt geht davon aus, dass die Unfälle sinken [2].",
                "The report quotes a range of 300 miles [2].",
                "Researchers say the camera reads every plate [2].",
                "Police report fewer accidents in the zones [2].",
                "A study found the camera reads every plate [2].",
                "Per the manufacturer, the battery lasts eight hours [2].",
                "The battery is rated at eight hours [2].",
                "The range is given as 300 miles [2].",
                "The camera is thought to read every plate [2].",
                "Measurements show that the camera reads every plate [2].",
                "Seiner Einschätzung nach erkennt die Kamera jedes Kennzeichen [2].",
                "Den Angaben nach erreicht die Kamera eine Trefferquote von 99 Prozent [2].",
                "Einer Studie nach, die 2023 erschien, sinken die Unfälle [2].",
                "Demnach erkennt die Kamera jedes Kennzeichen auch bei Nacht [2].",
                "Laut eigener Aussage erkennt die Kamera jedes Kennzeichen [2].",
                "Laut offiziellen Zahlen sinken die Unfälle [2].",
                "Die Kamera erkenne jedes Kennzeichen, so ein Sprecher am Montag auf Anfrage der Zeitung [2].",
                "Der Hersteller meldet eine Reichweite von 300 Kilometern [2].",
                "Müller weist auf die neuen Regeln hin [2]."}) {
            assertTrue(sentence, VisibleAttribution.reported(sentence));
            assertEquals(sentence, sentence, withSpeaker(sentence, 2, phrase));
        }
        for (String sentence : new String[]{
                "Die Sirene ist laut genug [2].",
                "Das Gerät ist so laut wie ein Föhn [2].",
                "Die Pumpe läuft laut auf hoher Stufe [2].",
                "Nach dem Umbau sinkt der Lärm [2].",
                "The camera reads 30 plates per second [2].",
                "The warning is given to the driver [2].",
                "The camera is put to use at night [2].",
                // the reviews of 22.11.2026: "laut" as an adjective, breakdowns and measurement bases, devices
                "Der Parksensor piept laut bei Annäherung an ein Hindernis [2].",
                "Die Reifen rollen laut über den Asphalt [2].",
                "Lkw sind laut im Stadtverkehr und belasten Anwohner [2].",
                "Die Unfallzahlen nach Bundesländern unterscheiden sich stark [2].",
                "Bei Messungen nach WLTP erreicht das Modell 400 Kilometer [2].",
                "Bei niedrigen Drehzahlen zeigt der Motor ein hohes Drehmoment [2].",
                "Der Totwinkelassistent warnt vor Fahrzeugen im toten Winkel [2].",
                "Die App meldet Blitzer in Echtzeit [2].",
                "Die Beschichtung garantiert eine Haltbarkeit von zehn Jahren [2].",
                "Ein Sensor stellt fest, ob der Fahrer die Hände am Lenkrad hat [2].",
                "Das System weist den Fahrer auf Gefahren hin [2].",
                "Das nennt man Notbremsfunktion [2].",
                "Diese Regel nennt man im Volksmund rechts vor links [2].",
                "Sensors warn the driver before a collision [2]."}) {
            assertFalse(sentence, VisibleAttribution.reported(sentence));
        }
    }

    /** The review of 22.11.2026: "so der Hersteller Sturzwacht" names the source's speaker with its name. */
    @Test
    public void aSpeakerWordWithTheSourcesNameAfterItCounts() {
        Collection<String> sturzwacht = VisibleAttribution.namesOf("https://source.sturzwacht.example/app");
        assertTrue(named("Die App erkennt jeden Sturz, so der Hersteller Sturzwacht [1].", 1, MAKER, sturzwacht));
        assertTrue(named("Die App erkennt jeden Sturz, wie der Hersteller Sturzwacht angibt [1].", 1, MAKER,
                sturzwacht));
        assertTrue(named("Der Hersteller Sturzwacht betont, dass die App jeden Sturz erkennt [1].", 1, MAKER,
                sturzwacht));
        assertTrue(named("Der Verband VDB empfiehlt mehr Tempo-30-Zonen [1].", 1, ASSOCIATION,
                VisibleAttribution.namesOf("portal.vdb.example")));
        assertTrue(named("Per Sturzwacht, the app detects every fall [1].", 1, MAKER, sturzwacht));
        Collection<String> fleet = VisibleAttribution.namesOf("https://www.flottenkamera.example/preise");
        assertTrue(named("Per the manufacturer, the camera detects every plate [2].", 2, MAKER, fleet));
        assertTrue(named("Per Flottenkamera, the camera detects every plate [2].", 2, MAKER, fleet));
        for (String distributive : new String[]{"The basic plan covers up to 20 vehicles per company [2].",
                "The system handles up to 500 cameras per vendor [2].", "Licences cost 49 euros per provider [2]."}) {
            assertFalse("a distributive per names no speaker: " + distributive, named(distributive, 2, MAKER, fleet));
        }
        assertFalse("another name after the speaker word", named("Die App erkennt jeden Sturz, so der Hersteller "
                + "Fallwerk [1].", 1, MAKER, sturzwacht));
        assertFalse(named("Der Hersteller Fallwerk betont, dass die App jeden Sturz erkennt [1].", 1, MAKER,
                sturzwacht));
        assertFalse("without the source's names no apposition", named("Die App erkennt jeden Sturz, so der "
                + "Hersteller Sturzwacht [1].", 1, MAKER));
    }

    /** The review of 22.11.2026: "nach VDB-Angaben", "laut Sturzwachts Angaben"; not "laut Blitzer-Angaben". */
    @Test
    public void aSourcesNameInACompoundOrAGenitiveFramesItsStatement() {
        Collection<String> vdb = VisibleAttribution.namesOf("portal.vdb.example");
        assertTrue(named("Nach VDB-Angaben sinken die Unfallzahlen [1].", 1, ASSOCIATION, vdb));
        assertTrue(named("VDB-Angaben zufolge sinken die Unfallzahlen [1].", 1, ASSOCIATION, vdb));
        assertTrue(named("Laut KFZV-Website sinken die Unfallzahlen [1].", 1, ASSOCIATION,
                VisibleAttribution.namesOf("kfzv.example")));
        Collection<String> sturzwacht = VisibleAttribution.namesOf("https://source.sturzwacht.example/app");
        assertTrue(named("Laut Sturzwachts Angaben erkennt die App jeden Sturz [1].", 1, MAKER, sturzwacht));
        assertTrue(named("Sturzwachts Angaben zufolge erkennt die App jeden Sturz [1].", 1, MAKER, sturzwacht));
        assertFalse("a long name in a compound names no source", named("Laut Blitzer-Angaben sinken die Unfälle "
                + "[1].", 1, MAKER, VisibleAttribution.namesOf("blitzer.example")));
        assertFalse("a short name only in capitals", named("Nach vdb-Angaben sinken die Unfallzahlen [1].", 1,
                ASSOCIATION, vdb));
        assertFalse("another source's compound", named("Nach KFZV-Angaben sinken die Unfallzahlen [1].", 1,
                ASSOCIATION, vdb));
    }

    /** The review of 22.11.2026: a site's own name by the public suffix list; none for a suffix or an address. */
    @Test
    public void aSitesNameComesFromThePublicSuffixList() {
        assertEquals("Verkehrsamt", VisibleAttribution.nameOf("https://www.verkehrsamt.gouv.fr/actualites"));
        assertEquals("a public suffix has no name of its own", "gouv.fr", VisibleAttribution.nameOf("gouv.fr"));
        assertEquals("Radinstitut", VisibleAttribution.nameOf("radinstitut.edu.au"));
        assertEquals("edu.au", VisibleAttribution.nameOf("edu.au"));
        assertEquals("KFZ", VisibleAttribution.nameOf("www.kfz.gov.uk"));
        assertEquals("gov.uk", VisibleAttribution.nameOf("gov.uk"));
        assertEquals("a suffix of the list's private part", "Verkehr", VisibleAttribution.nameOf("verkehr.eu.com"));
        assertEquals("eu.com", VisibleAttribution.nameOf("eu.com"));
        assertEquals("Radblog", VisibleAttribution.nameOf("radblog.blogspot.com"));
        assertEquals("blogspot.com", VisibleAttribution.nameOf("blogspot.com"));
        assertEquals("a site hosting others", "BSA", VisibleAttribution.nameOf("www.bsa.bund.de"));
        assertEquals("bund.de", VisibleAttribution.nameOf("bund.de"));
        assertEquals("an address", "192.168.10.20", VisibleAttribution.nameOf("http://192.168.10.20/status"));
        assertEquals("a label without a letter", "24.example", VisibleAttribution.nameOf("24.example"));
        assertEquals("a name with a number", "Rad24", VisibleAttribution.nameOf("rad24.example"));
        assertEquals(Collections.singletonList("gouv.fr"), VisibleAttribution.namesOf("gouv.fr"));
        assertEquals(Collections.singletonList("192.168.10.20"), VisibleAttribution.namesOf("192.168.10.20"));
        assertEquals(Collections.singletonList("24.example"), VisibleAttribution.namesOf("24.example"));
        assertEquals(Arrays.asList("xn--mnchen-3ya.example", "münchen.example", "münchen"),
                VisibleAttribution.namesOf("xn--mnchen-3ya.example"));
    }

    /** The review of 22.11.2026: the full stop of an abbreviation before the marker ends no sentence. */
    @Test
    public void noSpeakerGoesBeforeTheFullStopOfAnAbbreviation() {
        String phrase = ", wie ein Anbieter angibt";
        for (String sentence : new String[]{"Die Kamera erkennt Pkw, Lkw, Busse usw. [2]",
                "Die Anlage stammt von Schmidt & Co. [2]", "Die Kamera erkennt Pkw, Lkw u. a. [2]",
                "Die Kamera erkennt Pkw, Lkw usw. [2].", "Die Kamera erkennt Pkw, Lkw etc. [2]",
                "Die Kamera erkennt Pkw bzw. [2]", "Die Aktion startete 2019 bei Fuss e. V. [2]",
                "Die Aktion startete 2019 beim Verkehrsclub e.V. [2]", "Das Modell kostet 2.999 Euro inkl. MwSt. [2]",
                "Der Akku hält bis zu 8 Std. [2]", "Der Absatz stieg auf 1,2 Mio. [2]"}) {
            assertEquals(sentence, sentence, withSpeaker(sentence, 2, phrase));
        }
        assertEquals("a word that only ends like one", "Die Kamera filmt jeden Eingang der Disco, wie ein Anbieter "
                + "angibt. [2]", withSpeaker("Die Kamera filmt jeden Eingang der Disco. [2]", 2, phrase));
    }

    /** The review of 22.11.2026: a short name as the source writes it ("GdP"), also in a compound ("BASt-Angaben"). */
    @Test
    public void aShortNameCountsAsTheSourceWritesIt() {
        List<String> gdp = new ArrayList<String>(VisibleAttribution.namesOf("https://www.gdp.example/presse"));
        assertEquals(Arrays.asList("gdp.example", "GDP"), gdp);
        String phrase = ", wie ein Verband angibt";
        String sentence = "Die GdP hält mehr Blitzer für sinnvoll [2].";
        assertEquals("without its spelling the phrase goes in", "Die GdP hält mehr Blitzer für sinnvoll, wie ein "
                + "Verband angibt [2].", VisibleAttribution.withSpeaker(sentence, 2, gdp, phrase));
        gdp.add("GdP");
        assertEquals(sentence, VisibleAttribution.withSpeaker(sentence, 2, gdp, phrase));
        assertTrue(named("Die GdP fordert mehr Kontrollen auf Autobahnen [2].", 2, ASSOCIATION, gdp));
        assertFalse("a short name only as written", named("Die Gdp fordert mehr Kontrollen [2].", 2, ASSOCIATION,
                gdp));
        List<String> bast = new ArrayList<String>(VisibleAttribution.namesOf("https://www.bast.example/zahlen"));
        assertFalse(named("Nach BASt-Angaben sank die Zahl der Verkehrstoten [2].", 2, ASSOCIATION, bast));
        bast.add("BASt");
        assertTrue(named("Nach BASt-Angaben sank die Zahl der Verkehrstoten [2].", 2, ASSOCIATION, bast));
        assertTrue(named("Nach BAST-Angaben sank die Zahl der Verkehrstoten [2].", 2, ASSOCIATION, bast));
    }

    /** The review of 22.11.2026: a long hyphenated token costs no more than its length. */
    @Test
    public void aLongHyphenatedTokenStaysFast() {
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 750; i++) {
            token.append("A-");
        }
        String sentence = token + " [2].";
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            VisibleAttribution.reported(sentence);
            VisibleAttribution.withSpeaker(sentence, 2, NONE, ", wie ein Anbieter angibt");
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue("took " + millis + " ms", millis < 5_000);
    }

    @Test
    public void twoPassagesShareAGroupOnlyInOneBracket() {
        assertTrue(VisibleAttribution.shareAGroup("Die Kamera erkennt Fahrzeuge [2, 3].", 2, 3));
        assertTrue(VisibleAttribution.shareAGroup("Die Kamera erkennt Fahrzeuge [2; 3].", 3, 2));
        assertFalse(VisibleAttribution.shareAGroup("Die Kamera erkennt Fahrzeuge [2] und Radfahrer [3].", 2, 3));
        assertFalse(VisibleAttribution.shareAGroup(null, 2, 3));
    }
}
