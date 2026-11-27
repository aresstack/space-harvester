package com.aresstack.writing4j.sourcerating;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Component 16: hosts of addresses and the suggestions of the known list. */
public class KnownDomainsTest {

    @Test
    public void theHostOfAnAddress() {
        assertEquals("dguv.de", SourceSample.hostOf("https://www.dguv.de/de/praevention/index.jsp"));
        assertEquals("example.com", SourceSample.hostOf("http://WWW2.Example.COM:8080/x?y=1"));
        assertEquals("sub.example.org", SourceSample.hostOf("https://user:pw@sub.example.org/x"));
        assertEquals("example.com", SourceSample.hostOf("https://example.com./a#b"));
        assertEquals("bgbau.de", SourceSample.hostOf("bgbau.de"));
        assertEquals("", SourceSample.hostOf(""));
        assertEquals("", SourceSample.hostOf(null));
        assertEquals("the host from the address when none is given", "baua.de",
                new SourceSample("source-1", "https://www.baua.de/DE/Themen", "Titel", "", "Text").getHost());
    }

    @Test
    public void theListSuggestsTheMostSpecificDomain() {
        KnownDomains known = KnownDomains.shipped();
        KnownDomains.Entry dguv = known.suggest("https://publikationen.dguv.de/regelwerk/123");
        assertEquals("dguv.de", dguv.getDomain());
        assertEquals(SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG, dguv.getKind());
        assertEquals(SourceRating.Jurisdiction.DE, dguv.getJurisdiction());
        assertEquals(SourceRating.Kind.GESETZ_NORM, known.suggest("www.ris.bka.gv.at").getKind());
        assertEquals(SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG, known.suggest("www.oesterreich.gv.at").getKind());
        assertEquals(SourceRating.Kind.GESETZ_NORM, known.suggest("eur-lex.europa.eu").getKind());
        assertEquals(SourceRating.Jurisdiction.EU, known.suggest("osha.europa.eu").getJurisdiction());
        assertNull(known.suggest("https://www.example-wearables.com/blog"));
        assertNull(KnownDomains.none().suggest("dguv.de"));
    }

    @Test
    public void universitiesByRule() {
        KnownDomains known = KnownDomains.shipped();
        assertEquals(SourceRating.Jurisdiction.OHNE, known.suggest("https://news.mit.edu/x").getJurisdiction());
        assertEquals(SourceRating.Kind.WISSENSCHAFT, known.suggest("www.ox.ac.uk").getKind());
        assertEquals(SourceRating.Jurisdiction.ANDERE, known.suggest("www.ox.ac.uk").getJurisdiction());
        assertEquals(SourceRating.Jurisdiction.AT, known.suggest("www.tuwien.ac.at").getJurisdiction());
        assertEquals("uni-*.de", known.suggest("https://www.uni-mannheim.de/").getDomain());
        assertEquals(SourceRating.Jurisdiction.DE, known.suggest("tu-berlin.de").getJurisdiction());
        assertNull("a company named like a university prefix elsewhere", known.suggest("unicorn.com"));
    }

    @Test
    public void theShippedListIsValid() {
        KnownDomains known = KnownDomains.shipped();
        assertTrue(known.domains().size() >= 100);
        assertTrue(known.domains().contains("dguv.de"));
        for (String domain : known.domains()) {
            assertEquals("listed without www and in lower case", SourceSample.hostOf(domain), domain);
        }
    }

    @Test
    public void aRepeatedDomainIsRefused() {
        try {
            KnownDomains.parse("{\"domains\":[{\"domain\":\"dguv.de\",\"quellenart\":\"WISSENSCHAFT\","
                    + "\"rechtsraum\":\"DE\"},{\"domain\":\"www.dguv.de\",\"quellenart\":\"VERBAND\","
                    + "\"rechtsraum\":\"DE\"}]}");
            fail("www.dguv.de is dguv.de");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("dguv.de"));
        }
    }
}
