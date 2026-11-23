package com.aresstack.spaceharvester.mainframe.syntax;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;

import javax.swing.text.Segment;

/**
 * Hervorhebung für z/OS-JCL. MainframeMate hob JCL behelfsweise wie eine Properties-Datei hervor; dieser TokenMaker
 * kennt die Spalten der JCL-Anweisung wie die Grammatik {@code JCLLexer.g4}:
 * <ul>
 *   <li>{@code //*} Kommentarzeile, {@code /*} Ende der Eingabedaten</li>
 *   <li>{@code //NAME} Namensfeld (Job, Schritt, DD) als Funktion</li>
 *   <li>Operation (JOB, EXEC, DD, PROC, PEND, SET, IF/THEN/ELSE/ENDIF, INCLUDE, JCLLIB, OUTPUT …) als Schlüsselwort</li>
 *   <li>Schlüsselwortparameter ({@code DSN=}, {@code DISP=} …) als Variable, {@code &SYMBOL} als Präprozessor,
 *       Texte in Hochkommas, Zahlen, Trennzeichen</li>
 *   <li>alles nach den Operanden (durch Leerzeichen getrennt) als Kommentar</li>
 *   <li>Zeilen ohne {@code //} sind Eingabedaten (SYSIN) und bleiben unverändert</li>
 * </ul>
 */
public class JclTokenMaker extends AbstractTokenMaker {

    @Override
    public TokenMap getWordsToHighlight() {
        TokenMap map = new TokenMap(true);
        for (String op : new String[]{"JOB", "EXEC", "DD", "PROC", "PEND", "SET", "IF", "THEN", "ELSE", "ENDIF",
                "INCLUDE", "JCLLIB", "OUTPUT", "CNTL", "ENDCNTL", "XMIT", "COMMAND", "EXPORT", "SCHEDULE"}) {
            map.put(op, Token.RESERVED_WORD);
        }
        return map;
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
        if (startsWith(a, start, end, "//*")) {
            addToken(text, start, end - 1, Token.COMMENT_EOL, shift + start);
            addNullToken();
            return firstToken;
        }
        if (startsWith(a, start, end, "/*")) {
            addToken(text, start, end - 1, Token.PREPROCESSOR, shift + start);
            addNullToken();
            return firstToken;
        }
        if (!startsWith(a, start, end, "//")) {
            addToken(text, start, end - 1, Token.IDENTIFIER, shift + start);
            addNullToken();
            return firstToken;
        }

        addToken(text, start, start + 1, Token.SEPARATOR, shift + start);
        int i = start + 2;
        // Namensfeld direkt nach //
        int nameEnd = i;
        while (nameEnd < end && a[nameEnd] != ' ') {
            nameEnd++;
        }
        if (nameEnd > i) {
            addToken(text, i, nameEnd - 1, Token.FUNCTION, shift + i);
        }
        i = whitespace(text, nameEnd, end, shift);
        // Operation
        int opEnd = i;
        while (opEnd < end && a[opEnd] != ' ') {
            opEnd++;
        }
        if (opEnd > i) {
            int type = getWordsToHighlight().get(a, i, opEnd - 1);
            addToken(text, i, opEnd - 1, type == -1 ? Token.IDENTIFIER : type, shift + i);
        }
        i = whitespace(text, opEnd, end, shift);
        // Operanden bis zum ersten Leerzeichen außerhalb von Hochkommas
        boolean quoted = false;
        int tokenStart = i;
        while (i < end) {
            char c = a[i];
            if (quoted) {
                if (c == '\'') {
                    if (i + 1 < end && a[i + 1] == '\'') {
                        i += 2;
                        continue;
                    }
                    addToken(text, tokenStart, i, Token.LITERAL_STRING_DOUBLE_QUOTE, shift + tokenStart);
                    quoted = false;
                    tokenStart = i + 1;
                }
                i++;
                continue;
            }
            if (c == ' ') {
                break;
            }
            if (c == '\'') {
                quoted = true;
                tokenStart = i;
                i++;
                continue;
            }
            if (c == ',' || c == '(' || c == ')' || c == '=' || c == '.') {
                addToken(text, i, i, c == '=' ? Token.OPERATOR : Token.SEPARATOR, shift + i);
                i++;
                tokenStart = i;
                continue;
            }
            int wordEnd = i;
            while (wordEnd < end && " ,()='.".indexOf(a[wordEnd]) < 0) {
                wordEnd++;
            }
            int type;
            if (a[i] == '&') {
                type = Token.PREPROCESSOR;
            } else if (wordEnd < end && a[wordEnd] == '=') {
                type = Token.VARIABLE;
            } else if (allDigits(a, i, wordEnd)) {
                type = Token.LITERAL_NUMBER_DECIMAL_INT;
            } else {
                type = Token.IDENTIFIER;
            }
            addToken(text, i, wordEnd - 1, type, shift + i);
            i = wordEnd;
            tokenStart = i;
        }
        if (quoted && tokenStart < end) {
            addToken(text, tokenStart, end - 1, Token.LITERAL_STRING_DOUBLE_QUOTE, shift + tokenStart);
            i = end;
        }
        if (i < end) {
            addToken(text, i, end - 1, Token.COMMENT_EOL, shift + i);
        }
        addNullToken();
        return firstToken;
    }

    private int whitespace(Segment text, int from, int end, int shift) {
        int i = from;
        while (i < end && text.array[i] == ' ') {
            i++;
        }
        if (i > from) {
            addToken(text, from, i - 1, Token.WHITESPACE, shift + from);
        }
        return i;
    }

    private static boolean startsWith(char[] a, int start, int end, String prefix) {
        if (end - start < prefix.length()) {
            return false;
        }
        for (int k = 0; k < prefix.length(); k++) {
            if (a[start + k] != prefix.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allDigits(char[] a, int from, int to) {
        if (from >= to) {
            return false;
        }
        for (int k = from; k < to; k++) {
            if (!Character.isDigit(a[k])) {
                return false;
            }
        }
        return true;
    }
}
