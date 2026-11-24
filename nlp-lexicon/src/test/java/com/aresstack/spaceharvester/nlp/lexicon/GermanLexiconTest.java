package com.aresstack.spaceharvester.nlp.lexicon;

import com.aresstack.spaceharvester.nlp.lexicon.GermanLexicon.Modal;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanLexiconTest {

    @Test
    void negationsAndNegatingDeterminers() {
        assertTrue(GermanLexicon.isNegation("Nicht"));
        assertTrue(GermanLexicon.isNegation("niemals"));
        assertFalse(GermanLexicon.isNegation("kein"));
        assertTrue(GermanLexicon.isNegatingDeterminer("keinem"));
        assertFalse(GermanLexicon.isNegatingDeterminer(null));
    }

    @Test
    void modalsByLemmaOnlyForInfinitives() {
        assertEquals(Optional.of(Modal.MUESSEN), GermanLexicon.modalOfLemma("müssen"));
        assertEquals(Optional.empty(), GermanLexicon.modalOfLemma("muss"));
        assertEquals(Optional.empty(), GermanLexicon.modalOfLemma("werden"));
    }

    @Test
    void modalsByFormIncludeSubjunctive() {
        assertEquals(Optional.of(Modal.MUESSEN), GermanLexicon.modalOfForm("Müsste"));
        assertEquals(Optional.of(Modal.SOLLEN), GermanLexicon.modalOfForm("sollte"));
        assertEquals(Optional.of(Modal.KOENNEN), GermanLexicon.modalOfForm("könnten"));
        assertEquals(Optional.of(Modal.DUERFEN), GermanLexicon.modalOfForm("durfte"));
        assertEquals(Optional.empty(), GermanLexicon.modalOfForm("würde"));
    }
}
