package com.aresstack.spaceharvester.nlp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures.Article;
import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures.Features;
import org.junit.jupiter.api.Test;

/**
 * Begleiter des Rollennomens einer Zustandsaussage (ADR 0032, rules-13) an erfundenen Gold-Analysen: ein neutrales
 * Merkmal, keine Deutung; KEIN ändert nichts an der Verneinung.
 */
class RoleNounArticleTest {

    private static final ObjectCentricAnalysis ANALYSIS = Gold.analyze("artikel.conllu");

    private static Features features(int sentence) {
        Statement statement = ANALYSIS.statements().stream().filter(s -> s.sentence() == sentence - 1)
                .findFirst().orElseThrow(() -> new AssertionError("keine Aussage in Satz " + sentence));
        return StatementFeatures.assess(ANALYSIS, statement);
    }

    @Test
    void indefiniteDefiniteAndWithoutArticle() {
        assertEquals(Article.UNBESTIMMT, features(1).article(), "ist eine Behörde");
        assertEquals(Article.BESTIMMT, features(2).article(), "ist die Ansprechpartnerin");
        assertEquals(Article.OHNE, features(3).article(), "ist Sachbearbeiter");
        assertEquals(Article.OHNE, features(6).article(), "Mehrzahl ohne Artikel");
    }

    @Test
    void keinIsItsOwnValueAndStillNegated() {
        assertEquals(Article.KEIN, features(4).article());
        assertTrue(features(4).negated(), "„ist keine Förderung“ bleibt verneint");
        assertEquals(Article.BESTIMMT, features(5).article());
        assertTrue(features(5).negated(), "„ist nicht die Bewilligungsstelle“");
        assertFalse(features(1).negated());
    }

    @Test
    void anyOtherDeterminerIsSonstiger() {
        assertEquals(Article.SONSTIGER, features(7).article(), "ist unser Partner");
    }

    @Test
    void propertiesAndActionsHaveNoArticle() {
        assertNull(features(8).article(), "Eigenschaft");
        assertNull(features(9).article(), "Handlung");
    }
}
