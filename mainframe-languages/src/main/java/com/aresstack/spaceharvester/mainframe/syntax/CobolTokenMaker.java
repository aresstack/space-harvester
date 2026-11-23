package com.aresstack.spaceharvester.mainframe.syntax;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;

import javax.swing.text.Segment;

/**
 * Hervorhebung für COBOL im festen Format, ausgehend vom {@code CobolTokenMaker} aus MainframeMate (dort nur eine
 * Handvoll Schlüsselwörter und nicht eingebunden): Folgenummern (Spalte 1–6) und Kennungsbereich (ab Spalte 73) als
 * Dokumentationskommentar, {@code *} oder {@code /} in Spalte 7 als Kommentarzeile, {@code *>} als Zeilenkommentar,
 * Texte in Hochkommas oder Anführungszeichen, Zahlen, Schlüsselwörter (Divisions, Sections, Verben, Klauseln).
 */
public class CobolTokenMaker extends AbstractTokenMaker {

    private static final String[] RESERVED = {
            "IDENTIFICATION", "ID", "DIVISION", "PROGRAM-ID", "AUTHOR", "INSTALLATION", "DATE-WRITTEN",
            "DATE-COMPILED", "SECURITY", "ENVIRONMENT", "CONFIGURATION", "SOURCE-COMPUTER", "OBJECT-COMPUTER",
            "SPECIAL-NAMES", "INPUT-OUTPUT", "FILE-CONTROL", "I-O-CONTROL", "SELECT", "ASSIGN", "ORGANIZATION",
            "ACCESS", "RECORD", "KEY", "STATUS", "DATA", "FILE", "FD", "SD", "WORKING-STORAGE", "LOCAL-STORAGE",
            "LINKAGE", "SECTION", "PROCEDURE", "USING", "RETURNING", "COPY", "REPLACING", "END",
            "ACCEPT", "ADD", "CALL", "CANCEL", "CLOSE", "COMPUTE", "CONTINUE", "DELETE", "DISPLAY", "DIVIDE",
            "ELSE", "END-IF", "END-EVALUATE", "END-PERFORM", "END-READ", "END-CALL", "END-COMPUTE", "END-STRING",
            "END-UNSTRING", "END-SEARCH", "END-WRITE", "END-REWRITE", "END-START", "END-DELETE", "ENTRY",
            "EVALUATE", "EXIT", "GOBACK", "GO", "TO", "IF", "THEN", "INITIALIZE", "INSPECT", "MERGE", "MOVE",
            "MULTIPLY", "OPEN", "INPUT", "OUTPUT", "I-O", "EXTEND", "PERFORM", "UNTIL", "VARYING", "TIMES", "THRU",
            "THROUGH", "READ", "RELEASE", "RETURN", "REWRITE", "SEARCH", "SET", "SORT", "START", "STOP", "RUN",
            "STRING", "UNSTRING", "SUBTRACT", "WHEN", "OTHER", "WRITE", "AT", "INVALID", "NOT", "AND", "OR",
            "ALSO", "BY", "FROM", "INTO", "GIVING", "WITH", "DELIMITED", "SIZE", "ON", "OVERFLOW", "EXCEPTION",
            "EXEC", "END-EXEC", "SQL", "CICS", "DLI"
    };

    private static final String[] TYPES = {
            "PIC", "PICTURE", "VALUE", "VALUES", "REDEFINES", "OCCURS", "DEPENDING", "INDEXED", "COMP", "COMP-1",
            "COMP-2", "COMP-3", "COMP-4", "COMP-5", "BINARY", "PACKED-DECIMAL", "DISPLAY-1", "USAGE", "SIGN",
            "LEADING", "TRAILING", "SEPARATE", "JUSTIFIED", "JUST", "SYNC", "SYNCHRONIZED", "FILLER", "BLANK",
            "ZERO", "ZEROS", "ZEROES", "SPACE", "SPACES", "HIGH-VALUE", "HIGH-VALUES", "LOW-VALUE", "LOW-VALUES",
            "QUOTE", "QUOTES", "TRUE", "FALSE", "NULL", "NULLS"
    };

    private TokenMap words;

    @Override
    public TokenMap getWordsToHighlight() {
        if (words == null) {
            words = new TokenMap(true);
            for (String w : RESERVED) {
                words.put(w, Token.RESERVED_WORD);
            }
            for (String w : TYPES) {
                words.put(w, Token.DATA_TYPE);
            }
        }
        return words;
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        char[] a = text.array;
        int start = text.offset;
        int end = start + text.count;
        int shift = startOffset - start;
        if (text.count == 0) {
            addNullToken();
            return firstToken;
        }
        // Folgenummernbereich
        int area = Math.min(end, start + 6);
        if (area > start) {
            addToken(text, start, area - 1, Token.COMMENT_DOCUMENTATION, shift + start);
        }
        if (area >= end) {
            addNullToken();
            return firstToken;
        }
        char indicator = a[area];
        int codeEnd = Math.min(end, start + 72);
        if (indicator == '*' || indicator == '/') {
            addToken(text, area, end - 1, Token.COMMENT_EOL, shift + area);
            addNullToken();
            return firstToken;
        }
        addToken(text, area, area, indicator == ' ' ? Token.WHITESPACE : Token.PREPROCESSOR, shift + area);
        int i = area + 1;
        while (i < codeEnd) {
            char c = a[i];
            if (c == '*' && i + 1 < codeEnd && a[i + 1] == '>') {
                addToken(text, i, codeEnd - 1, Token.COMMENT_EOL, shift + i);
                i = codeEnd;
                break;
            }
            if (Character.isWhitespace(c)) {
                int j = i;
                while (j < codeEnd && Character.isWhitespace(a[j])) {
                    j++;
                }
                addToken(text, i, j - 1, Token.WHITESPACE, shift + i);
                i = j;
            } else if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < codeEnd && a[j] != c) {
                    j++;
                }
                int last = Math.min(j, codeEnd - 1);
                addToken(text, i, last, Token.LITERAL_STRING_DOUBLE_QUOTE, shift + i);
                i = last + 1;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < codeEnd && (Character.isDigit(a[j]) || (a[j] == '.' && j + 1 < codeEnd
                        && Character.isDigit(a[j + 1])))) {
                    j++;
                }
                if (j < codeEnd && (Character.isLetter(a[j]) || a[j] == '-')) {
                    j = word(a, i, codeEnd);
                    addToken(text, i, j - 1, Token.IDENTIFIER, shift + i);
                } else {
                    addToken(text, i, j - 1, Token.LITERAL_NUMBER_DECIMAL_INT, shift + i);
                }
                i = j;
            } else if (Character.isLetter(c)) {
                int j = word(a, i, codeEnd);
                int type = getWordsToHighlight().get(a, i, j - 1);
                addToken(text, i, j - 1, type == -1 ? Token.IDENTIFIER : type, shift + i);
                i = j;
            } else if (c == '.' || c == ',' || c == '(' || c == ')') {
                addToken(text, i, i, Token.SEPARATOR, shift + i);
                i++;
            } else {
                addToken(text, i, i, Token.OPERATOR, shift + i);
                i++;
            }
        }
        if (codeEnd < end) {
            addToken(text, codeEnd, end - 1, Token.COMMENT_DOCUMENTATION, shift + codeEnd);
        }
        addNullToken();
        return firstToken;
    }

    private static int word(char[] a, int from, int end) {
        int j = from;
        while (j < end && (Character.isLetterOrDigit(a[j]) || a[j] == '-' || a[j] == '_')) {
            j++;
        }
        return j;
    }
}
