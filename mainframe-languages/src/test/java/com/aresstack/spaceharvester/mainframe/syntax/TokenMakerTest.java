package com.aresstack.spaceharvester.mainframe.syntax;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.junit.jupiter.api.Test;

import javax.swing.text.Segment;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenMakerTest {

    static Map<String, Integer> tokens(TokenMaker maker, String line) {
        Segment segment = new Segment(line.toCharArray(), 0, line.length());
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Token t = maker.getTokenList(segment, Token.NULL, 0); t != null && t.isPaintable(); t = t.getNextToken()) {
            if (t.getType() != Token.WHITESPACE) {
                result.put(t.getLexeme(), t.getType());
            }
        }
        return result;
    }

    @Test
    void jclStatementHasNameOperationParametersAndComment() {
        Map<String, Integer> t = tokens(new JclTokenMaker(), "//SCHRITT1 EXEC PGM=IEFBR14,PARM='A B' KOMMENTAR");

        assertThat(t).containsEntry("SCHRITT1", Token.FUNCTION).containsEntry("EXEC", Token.RESERVED_WORD)
                .containsEntry("PGM", Token.VARIABLE).containsEntry("'A B'", Token.LITERAL_STRING_DOUBLE_QUOTE)
                .containsEntry(" KOMMENTAR", Token.COMMENT_EOL);
        assertThat(tokens(new JclTokenMaker(), "//* Kommentar")).containsValue(Token.COMMENT_EOL);
        assertThat(tokens(new JclTokenMaker(), "//EIN DD DSN=&HLQ..DATEN")).containsEntry("&HLQ", Token.PREPROCESSOR);
    }

    @Test
    void cobolKnowsColumnsCommentsAndKeywords() {
        assertThat(tokens(new CobolTokenMaker(), "000100     MOVE 'X' TO WS-FELD.     "))
                .containsEntry("000100", Token.COMMENT_DOCUMENTATION).containsEntry("MOVE", Token.RESERVED_WORD)
                .containsEntry("'X'", Token.LITERAL_STRING_DOUBLE_QUOTE).containsEntry("WS-FELD", Token.IDENTIFIER);
        assertThat(tokens(new CobolTokenMaker(), "000200* Kommentar")).containsEntry("* Kommentar", Token.COMMENT_EOL);
        assertThat(tokens(new CobolTokenMaker(), "       01 A PIC 9(4).")).containsEntry("PIC", Token.DATA_TYPE);
    }

    @Test
    void naturalFromMainframeMate() {
        assertThat(tokens(new NaturalTokenMaker(), "CALLNAT 'UNTERPRG' #ZAEHLER"))
                .containsEntry("CALLNAT", Token.RESERVED_WORD).containsEntry("#ZAEHLER", Token.VARIABLE);
    }

    @Test
    void registersAllThreeStyles() {
        MainframeSyntax.register();
        MainframeSyntax.register();

        assertThat(TokenMakerFactory.getDefaultInstance().getTokenMaker(MainframeSyntax.JCL))
                .isInstanceOf(JclTokenMaker.class);
        assertThat(TokenMakerFactory.getDefaultInstance().getTokenMaker(MainframeSyntax.COBOL))
                .isInstanceOf(CobolTokenMaker.class);
        assertThat(TokenMakerFactory.getDefaultInstance().getTokenMaker(MainframeSyntax.NATURAL))
                .isInstanceOf(NaturalTokenMaker.class);
    }
}
