package com.aresstack.spaceharvester.deck.terminal.openterm;

import com.ascert.open.term.i3270.Tn3270StreamParser;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OpenTerm übersetzt EBCDIC fest nach CP037 (US). Deutsche Hosts nutzen CP273, wo etwa 0xC0 „ä“ ist und nicht „{“.
 * Wie in MainframeMate werden die Tabellen des Parsers vor dem Anlegen eines Terminals aus der Java-Codepage
 * neu gefüllt. Die Tabellen sind statisch und gelten für alle Sitzungen zugleich: Solange eine Sitzung offen ist,
 * nimmt keine zweite mit anderer Codepage sie in Beschlag ({@link #acquire}); sonst würden Umlaute der ersten zu
 * Klammern und Eingaben verfälscht.
 */
final class CodePages {

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static String applied;
    /** Offene Verbindungen, die die gesetzte Codepage nutzen. */
    private static int users;

    private CodePages() {
    }

    /**
     * Setzt die Codepage für eine neue Verbindung und zählt sie als Nutzer. Ist schon eine Verbindung mit einer
     * anderen Codepage offen, bleibt alles, wie es ist, und es kommt eine lesbare Ablehnung.
     */
    static void acquire(String codePage) {
        LOCK.lock();
        try {
            if (users > 0 && !codePage.equals(applied)) {
                throw new IllegalStateException("Codepage " + codePage + " geht nicht, solange eine Sitzung mit "
                        + applied + " offen ist; erst diese trennen");
            }
            apply(codePage);
            users++;
        } finally {
            LOCK.unlock();
        }
    }

    /** Eine Verbindung ist zu und nutzt die Codepage nicht mehr. */
    static void release() {
        LOCK.lock();
        try {
            users = Math.max(0, users - 1);
        } finally {
            LOCK.unlock();
        }
    }

    static int users() {
        LOCK.lock();
        try {
            return users;
        } finally {
            LOCK.unlock();
        }
    }

    static void apply(String codePage) {
        LOCK.lock();
        try {
            if (codePage.equals(applied)) {
                return;
            }
            Charset charset = Charset.forName(codePage);
            System.arraycopy(ebcdicToUnicode(charset), 0, Tn3270StreamParser.ebc2asc, 0, 256);
            System.arraycopy(unicodeToEbcdic(charset), 0, Tn3270StreamParser.asc2ebc, 0, 256);
            applied = codePage;
        } finally {
            LOCK.unlock();
        }
    }

    static int[] ebcdicToUnicode(Charset charset) {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            table[i] = new String(new byte[] {(byte) i}, charset).charAt(0);
        }
        return table;
    }

    static int[] unicodeToEbcdic(Charset charset) {
        int[] table = new int[256];
        CharsetEncoder encoder = charset.newEncoder()
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .onMalformedInput(CodingErrorAction.REPLACE);
        for (int i = 0; i < 256; i++) {
            try {
                ByteBuffer encoded = encoder.encode(CharBuffer.wrap(new char[] {(char) i}));
                table[i] = encoded.get(0) & 0xFF;
            } catch (CharacterCodingException e) {
                table[i] = 0;
            }
        }
        return table;
    }
}
