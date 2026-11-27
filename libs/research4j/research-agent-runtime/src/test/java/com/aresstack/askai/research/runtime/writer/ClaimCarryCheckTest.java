package com.aresstack.askai.research.runtime.writer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Stage 2 of the claim check against the sections written in live run 3. */
public class ClaimCarryCheckTest {

    private final ClaimCarryCheck check = new ClaimCarryCheck(ClaimCarryCheck.Criteria.defaults());

    private static List<EvidencePassage> passages(String[] texts) {
        List<EvidencePassage> out = new ArrayList<EvidencePassage>();
        for (int i = 0; i < texts.length; i++) {
            if (!texts[i].isEmpty()) {
                out.add(new EvidencePassage(i + 1, "s" + (i + 1), "c", "p" + (i + 1), texts[i]));
            }
        }
        return out;
    }

    private List<ClaimFlag> flags(String section, String[] texts) {
        List<EvidencePassage> passages = passages(texts);
        return check.check(new CitationValidator().validate(section, texts.length), passages);
    }

    private static int sentences(String section, int k) {
        int n = 0;
        for (CitationValidator.Paragraph p : new CitationValidator().validate(section, k).getParagraphs()) {
            n += p.getSentences().size();
        }
        return n;
    }

    @Test
    public void everySentenceOfSection13FailsBecauseItCitesAFooter() {
        List<ClaimFlag> flags = flags(LiveRun3Fixtures.SECTION_1_3, LiveRun3Fixtures.SECTION_1_3_PASSAGES);
        assertEquals(sentences(LiveRun3Fixtures.SECTION_1_3, 6), flags.size());
        for (ClaimFlag flag : flags) {
            assertEquals(ClaimFlag.Kind.UNCARRIED_CLAIM, flag.getKind());
            assertTrue(flag.getDetail(), flag.getDetail().startsWith("[6] is no prose"));
        }
    }

    @Test
    public void section12IsCarriedSentenceBySentence() {
        assertEquals(new ArrayList<ClaimFlag>(),
                flags(LiveRun3Fixtures.SECTION_1_2, LiveRun3Fixtures.SECTION_1_2_PASSAGES));
    }

    @Test
    public void inSection11TheBreadcrumbCitationsAndTheUnsupportedGeneralisationsFail() {
        List<String> failed = new ArrayList<String>();
        for (ClaimFlag flag : flags(LiveRun3Fixtures.SECTION_1_1, LiveRun3Fixtures.SECTION_1_1_PASSAGES)) {
            failed.add(flag.getSentence().substring(0, 30));
        }
        // [3] is the dormakaba breadcrumb; [1] says nothing about "traditional methods" or "motion or temperature".
        assertEquals(java.util.Arrays.asList("Die Technologien und Sensortyp", "Diese Technologien ermöglichen",
                "Smartwatches und Sensorarmbänd", "Diese Wearables können verschi"), failed);
    }

    @Test
    public void inSection14TheHeadingCitationsAndTheSelfTrackingClaimFail() {
        List<String> failed = new ArrayList<String>();
        for (ClaimFlag flag : flags(LiveRun3Fixtures.SECTION_1_4, LiveRun3Fixtures.SECTION_1_4_PASSAGES)) {
            failed.add(flag.getSentence().substring(0, 30) + " | " + flag.getDetail());
        }
        assertEquals(java.util.Arrays.asList(
                "Die Implementierung von Wearab | [1] is no prose (8 words < 10)",
                "Die Kostenanalyse umfasst die  | [6] is no prose (5 words < 10)",
                "Dennoch können Wearables zur S | overlap 0.21 < 0.30"), failed);
    }

    @Test
    public void theThresholdIsASetting() {
        ClaimCarryCheck lenient = new ClaimCarryCheck(new ClaimCarryCheck.Criteria(4, 5, 0.1,
                ProsePassageFilter.Criteria.defaults()));
        List<ClaimFlag> flags = lenient.check(new CitationValidator().validate(LiveRun3Fixtures.SECTION_1_4, 8),
                passages(LiveRun3Fixtures.SECTION_1_4_PASSAGES));
        assertEquals("only the two heading citations remain", 2, flags.size());
    }

    @Test
    public void theRevisionLineKeepsTheSentenceAndTheReason() {
        ClaimFlag flag = flags(LiveRun3Fixtures.SECTION_1_3, LiveRun3Fixtures.SECTION_1_3_PASSAGES).get(0);
        assertTrue(flag.revisionLine(), flag.revisionLine().startsWith(
                "citation does not carry the claim: Die rechtlichen Rahmenbedingungen"));
        assertTrue(flag.revisionLine(), flag.revisionLine().endsWith("words without punctuation > 30))"));
    }
}
