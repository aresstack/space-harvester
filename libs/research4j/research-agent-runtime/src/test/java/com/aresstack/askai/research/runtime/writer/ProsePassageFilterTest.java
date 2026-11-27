package com.aresstack.askai.research.runtime.writer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The boilerplate filter against the real evidence of live run 3. */
public class ProsePassageFilterTest {

    private final ProsePassageFilter filter = new ProsePassageFilter(ProsePassageFilter.Criteria.defaults());

    @Test
    public void everyNavigationAndHeadingPassageOfLiveRun3IsBoilerplate() {
        for (String passage : LiveRun3Fixtures.BOILERPLATE) {
            assertFalse(passage, filter.isProse(passage));
        }
    }

    @Test
    public void everyProsePassageOfLiveRun3Stays() {
        for (String passage : LiveRun3Fixtures.PROSE) {
            assertTrue(filter.reason(passage), filter.isProse(passage));
        }
    }

    @Test
    public void theReasonNamesTheCriterion() {
        assertTrue(filter.reason(LiveRun3Fixtures.HEXAGON_NAVIGATION), filter.reason(
                LiveRun3Fixtures.HEXAGON_NAVIGATION).contains("without punctuation"));
        assertEquals("5 words < 10", filter.reason(LiveRun3Fixtures.HEADING_COSTS));
        assertTrue(filter.reason(LiveRun3Fixtures.DORMAKABA_BREADCRUMB).contains("finished sentences"));
    }

    @Test
    public void theCriteriaAreSettings() {
        ProsePassageFilter lenient = new ProsePassageFilter(new ProsePassageFilter.Criteria(0, 0, 200,
                ProsePassageFilter.Criteria.DEFAULT_CAPTION_MAX_WORDS, 0, 0, 1.0, 1.0, Integer.MAX_VALUE));
        assertTrue(lenient.isProse(LiveRun3Fixtures.HEXAGON_NAVIGATION));
        ProsePassageFilter strict = new ProsePassageFilter(new ProsePassageFilter.Criteria(20, 1, 30));
        assertFalse("12 words", strict.isProse(LiveRun3Fixtures.HEXAGON_GNSS));
    }

    /** Live run 4: footer lines passed because symbols and numbers counted as words. */
    @Test
    public void onlyTokensWithALetterAreWords() {
        assertEquals("5 words < 10", filter.reason("© 2026 Oxeltech GmbH · Mahlsdorfer Str. 10, 12555 Berlin"));
        assertFalse(filter.isProse("FSS sp. z o.o. ul. Agawy 7\n43-300 Bielsko-Biała, Polen"));
        assertTrue(filter.isProse("Smarte Helme melden Stürze sofort an die Leitstelle, sodass Ersthelfer "
                + "schneller eintreffen."));
    }

    /**
     * Page-noise measurement of 22.11.2026: a heading with soft hyphens counted every part as a word of its own and
     * passed as prose. A soft hyphen joins the parts of one word.
     */
    @Test
    public void aSoftHyphenJoinsTheWordItBreaks() {
        String heading = "Gefährdungs\u00ADbeurteilung für Bild\u00ADschirm\u00ADarbeits\u00ADplätze nach der "
                + "Arbeits\u00ADstätten\u00ADverordnung.";
        assertEquals("6 words < 10", filter.reason(heading));
        assertEquals(6, ProsePassageFilter.words(heading));
        assertTrue(filter.isProse("Die Gefährdungs\u00ADbeurteilung muss vor der Aufnahme der Tätigkeit stehen, "
                + "und sie wird bei jeder wesentlichen Änderung wiederholt."));
    }

    /** The page-noise check (22.11.2026) names the kind of leftover by the rule a passage breaks. */
    @Test
    public void theRuleNamesTheKindOfLeftover() {
        assertEquals(ProsePassageFilter.Rule.WORDS, filter.rule(LiveRun3Fixtures.HEADING_COSTS));
        assertEquals(ProsePassageFilter.Rule.SENTENCES, filter.rule(LiveRun3Fixtures.DORMAKABA_BREADCRUMB));
        assertEquals(ProsePassageFilter.Rule.RUN, filter.rule(LiveRun3Fixtures.HEXAGON_NAVIGATION));
        assertEquals(ProsePassageFilter.Rule.SITE_NOTICES, filter.rule("Die Website der EU-OSHA verwendet Cookies, "
                + "um Informationen zu sammeln, die für die Navigation auf der Website erforderlich sind und die uns "
                + "helfen, sie zu verbessern."));
        assertEquals(ProsePassageFilter.Rule.PROMOTION, filter.rule("-10 % Sofortrabatt mit dem Code DS26AD8 auf alle "
                + "Messgeräte. Der Gutschein ist mehrfach einlösbar und gilt bis Ende des Monats."));
        assertEquals(ProsePassageFilter.Rule.CAPITALS, filter.rule("DIE NEUE REGEL GILT AB SOFORT FÜR ALLE FAHRER IM "
                + "GANZEN LAND UND IN JEDER STADT."));
        assertEquals(ProsePassageFilter.Rule.LINKS, filter.rule("Weitere Informationen finden Sie unter "
                + "www.example.org, https://example.org/a und https://example.org/b sowie www.example.net."));
        String prose = "Das Arbeitsschutzgesetz verpflichtet den Arbeitgeber, die Gefährdungen auf der Baustelle zu "
                + "beurteilen und geeignete Schutzmaßnahmen festzulegen.";
        assertEquals(ProsePassageFilter.Rule.NONE, filter.rule(prose));
        assertEquals("", filter.reason(prose));
    }

    /**
     * Acceptance of the page-noise check, 22.11.2026: the notice rule dropped two passages of content on a single word.
     * Asked to, it spares one notice that is also a word of prose; a credit, a law header, two notices or a cookie
     * notice still break it, and the other rules still apply.
     */
    @Test
    public void aSingleNoticeThatIsAWordOfProseCanBeSpared() {
        String consent = "Beschäftigte können ihre Einwilligung widerrufen, ohne dass ihnen daraus Nachteile entstehen "
                + "dürfen; die Daten der Sensorweste sind dann zu löschen.";
        String faq = "Nach den FAQ der Aufsichtsbehörde ist eine Ortung während der Pausen unzulässig, und der "
                + "Betriebsrat muss der Einführung zustimmen.";
        assertEquals(ProsePassageFilter.Rule.SITE_NOTICES, filter.rule(consent));
        assertEquals(ProsePassageFilter.Rule.NONE, filter.rule(consent, true));
        assertEquals("", filter.reason(consent, true));
        assertEquals(ProsePassageFilter.Rule.NONE, filter.rule(faq, true));
        assertEquals("1 site notices > 0", filter.reason(faq, false));
        for (String notice : new String[]{
                "Bauarbeiter mit Sensorweste auf dem Gerüst einer Großbaustelle in der Stadt. Bildquelle: Beispiel AG",
                "BaustellV – Ausfertigungsdatum: 10.06.1998, zuletzt geändert durch Artikel 1 der Verordnung vom 19. "
                        + "Dezember.",
                "FAQ Häufig gestellte Fragen zu Wearables am Arbeitsplatz und zur Beteiligung des Betriebsrats bei der "
                        + "Einführung.",
                "Die Website der EU-OSHA verwendet Cookies, um Informationen zu sammeln, die für die Navigation auf "
                        + "der Website erforderlich sind und die uns helfen, sie zu verbessern."}) {
            assertEquals(notice, ProsePassageFilter.Rule.SITE_NOTICES, filter.rule(notice, true));
        }
        assertEquals("the other rules still apply", ProsePassageFilter.Rule.LINKS, filter.rule("Die FAQ stehen unter "
                + "www.example.org, https://example.org/a und https://example.org/b sowie www.example.net.", true));
    }

    /** Live run 4: calendar slogans between the product lines of a shop page formed a topic of their own. */
    @Test
    public void aLoneShortSentenceOnAPageOfAlmostNoProseIsACaption() {
        java.util.List<String> shop = new java.util.ArrayList<String>();
        for (int i = 0; i < 60; i++) { // 2 prose passages of 60: below the share 0.05
            shop.add("2026 Link Kalender Wandkalender");
        }
        shop.set(10, "Refresh your first aid training regularly to be prepared for emergencies.");
        shop.set(20, "Das Arbeitsschutzgesetz regelt für alle Tätigkeitsbereiche die grundlegenden Pflichten des "
                + "Arbeitgebers, die Gefährdungen am Arbeitsplatz zu beurteilen, geeignete Maßnahmen zu treffen "
                + "und alles schriftlich festzuhalten.");

        assertEquals("the long sentence is no caption", java.util.Collections.singleton(10), filter.captions(shop));

        assertTrue("a page with enough prose has no captions",
                filter.captions(java.util.Arrays.asList("Kontakt", shop.get(10), "Impressum")).isEmpty());
        ProsePassageFilter off = new ProsePassageFilter(new ProsePassageFilter.Criteria(10, 1, 30, 22, 0));
        assertTrue("share 0 turns the rule off", off.captions(shop).isEmpty());
    }

    /** Live retest of #240: advertising, navigation and link lists that pass the sentence rules reached the outline. */
    @Test
    public void advertisingNavigationAndLinkListsAreNoProse() {
        ProsePassageFilter filter = new ProsePassageFilter(ProsePassageFilter.Criteria.defaults());
        assertFalse(filter.isProse("JETZT TICKET SICHERN! Zurück zur Übersicht, Vorheriger Beitrag, Nächster Beitrag, "
                + "Suche und neueste Beiträge im Überblick."));
        assertFalse(filter.isProse("Sie gehen kein Risiko ein: Jederzeit formlos zu sofort kündbar, und das erste "
                + "Heft bekommen Sie gratis."));
        assertFalse(filter.isProse("Seminare für Betriebsräte im ganzen Land: Katalog kostenlos bestellen und die Termine sichern."));
        assertFalse(filter.isProse("Weitere Informationen finden Sie unter www.example.org, https://example.org/a "
                + "und https://example.org/b sowie www.example.net."));
        assertFalse(filter.isProse("Nehmen Sie jetzt Kontakt auf und lassen Sie uns Ihre DGUV Vorschrift 3 "
                + "Dokumentation gemeinsam planen und umsetzen."));
        assertFalse(filter.isProse("Unsere Empfehlungen für Sie: Ansell AlphaTec® 1500 Schutzanzug, bewährt in "
                + "Chemie und Industrie."));
        assertFalse(filter.isProse("teilen teilen teilen Per E-Mail teilen Info Quicklinks zum Thema Arbeitsschutz "
                + "auf der Baustelle."));
        assertTrue(filter.isProse("Das Arbeitsschutzgesetz verpflichtet den Arbeitgeber, die Gefährdungen auf der "
                + "Baustelle zu beurteilen und geeignete Schutzmaßnahmen festzulegen. Die Beurteilung ist zu "
                + "dokumentieren und bei Änderungen zu wiederholen."));
        assertTrue("a single price in a long statement stays", filter.isProse("Ein Sensorwestensystem kostet nach "
                + "Herstellerangaben etwa 300 € je Beschäftigtem, wobei Wartung und Schulung in den Studien getrennt "
                + "ausgewiesen werden und die Amortisation von der Unfallquote des Betriebs abhängt."));
        ProsePassageFilter off = new ProsePassageFilter(new ProsePassageFilter.Criteria(10, 1, 30, 22, 0.05, 0, 1.0,
                1.0));
        assertTrue("the rules can be turned off", off.isProse("Seminare für Betriebsräte im ganzen Land: Katalog "
                + "kostenlos bestellen und die Termine sichern."));
    }

    /** Live retest of #251: photo credits, licence texts, order hints and link stubs reached sections. */
    @Test
    public void creditsLicencesAndOrderHintsAreSiteNotices() {
        ProsePassageFilter filter = new ProsePassageFilter(ProsePassageFilter.Criteria.defaults());
        assertFalse(filter.isProse("Bauarbeiter mit Sensorweste auf dem Gerüst einer Großbaustelle in der Stadt. "
                + "© PeopleImages/istockphoto"));
        assertFalse(filter.isProse("Dieser Beitrag steht unter der Lizenz Creative Commons CC BY-SA 4.0 und darf "
                + "unter Nennung der Quelle weiterverwendet werden."));
        assertFalse(filter.isProse("Für Bestellungen bis zum 22. Dezember garantieren wir die Lieferung vor den "
                + "Feiertagen an alle Adressen."));
        assertFalse(filter.isProse("Weitere Informationen zur Studie finden Sie online. WWW: https://www.example.org "
                + "mit allen Anhängen und Tabellen."));
    }

    /** Live retest of #247: a footer, a figure caption, a reference entry and a law header became topics. */
    @Test
    public void footersCaptionsAndReferencesAreSiteNotices() {
        ProsePassageFilter filter = new ProsePassageFilter(ProsePassageFilter.Criteria.defaults());
        assertFalse(filter.isProse("Startseite Impressum Datenschutzerklärung. Wie gefällt Ihnen diese Website? "
                + "Rate jetzt mit einem bis fünf Sternen."));
        assertFalse(filter.isProse("Abb. 3: Die Sensorweste misst Puls, Hauttemperatur und Bewegung der "
                + "Beschäftigten auf der Baustelle."));
        assertFalse(filter.isProse("Evers, M. (2021): Wearables im Arbeitsschutz. Eine Übersicht über Studien "
                + "und Praxisberichte, Berlin."));
        assertFalse(filter.isProse("BaustellV – Ausfertigungsdatum: 10.06.1998, zuletzt geändert durch Artikel 1 "
                + "der Verordnung vom 19. Dezember."));
    }

    /** Live retest of #245: shop coupons and product teasers became topics. */
    @Test
    public void couponsAndProductTeasersAreAdvertising() {
        ProsePassageFilter filter = new ProsePassageFilter(ProsePassageFilter.Criteria.defaults());
        assertFalse(filter.isProse("-10 % Sofortrabatt mit dem Code DS26AD8 auf alle Messgeräte. Der Gutschein ist "
                + "mehrfach einlösbar und gilt bis Ende des Monats."));
        assertFalse(filter.isProse("Schützen Sie Ihre Mitarbeitenden mit den zertifizierten TWIG-Lösungen für "
                + "Alleinarbeit."));
        assertFalse(filter.isProse("Fluke Produkt kaufen und Geschenk erhalten, so lange der Vorrat reicht."));
    }

    /** Live retest of #243: cookie banners, an FAQ header and a list of internal references became sections. */
    @Test
    public void siteNoticesAreNoProse() {
        ProsePassageFilter filter = new ProsePassageFilter(ProsePassageFilter.Criteria.defaults());
        assertFalse(filter.isProse("Die Website der EU-OSHA verwendet Cookies, um Informationen zu sammeln, die für "
                + "die Navigation auf der Website erforderlich sind und die uns helfen, sie zu verbessern."));
        assertFalse(filter.isProse("FAQ Häufig gestellte Fragen zu Wearables am Arbeitsplatz und zur Beteiligung "
                + "des Betriebsrats bei der Einführung."));
        assertFalse(filter.isProse("Nachfolgend finden Sie eine Auswahl interner Fundstellen zum Thema Arbeitsschutz "
                + "und Digitalisierung in der Pflege."));
        assertFalse(filter.isProse("Mit Ihrer Einwilligung nutzen wir Daten für Statistiken und Marketing. Sie können "
                + "die Datenschutzeinstellungen jederzeit ändern. Zustimmen Ablehnen"));
        assertTrue("consent as a subject stays", filter.isProse("Die Einwilligung der Beschäftigten ist im "
                + "Arbeitsverhältnis nur wirksam, wenn sie freiwillig erteilt wird; wer sie ablehnt, darf keine "
                + "Nachteile erleiden."));
        assertTrue(filter.isProse("Die Studie zeigt, dass Beschäftigte die Daten ihrer Sensorwesten nur dann "
                + "akzeptieren, wenn der Zweck vorab festgelegt und die Auswertung anonymisiert ist."));
        ProsePassageFilter off = new ProsePassageFilter(new ProsePassageFilter.Criteria(10, 1, 30, 22, 0.05, 1,
                0.4, 0.05, 5));
        assertTrue("a higher limit lets a notice pass", off.isProse("Die Website der EU-OSHA verwendet Cookies, um "
                + "Informationen zu sammeln, die für die Navigation auf der Website erforderlich sind."));
    }
}
