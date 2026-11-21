package com.aresstack.enterpriseai.astu.acropolis.speech;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bereitet eine Markdown-Antwort zum Vorlesen auf: Codeblöcke und Mermaid-Diagramme fallen weg, Links werden zu
 * ihrem Text, Formatierungszeichen verschwinden. {@link #chunks(String, SpeechChunking)} teilt nach der gewählten
 * {@link SpeechChunking} (Absatz-/Satz-Körnung aus askai arch; ohne Textanalyse ein einfacher Schnitt an Satzzeichen, mit ihr
 * schneidet {@link TextAnalysisService} die Sätze über OpenNLP).
 */
public final class SpeechText {

    /** Obergrenze je Satz; längere Sätze werden an Kommas oder Leerzeichen geteilt. */
    static final int MAX_CHUNK_CHARS = 280;

    /**
     * Obergrenze je Absatz- oder Antwort-Stück: längere werden an Satzgrenzen geteilt, damit eine Äußerung im
     * Token-Rahmen der Stimme bleibt und das erste Stück nicht unnötig lange rechnet.
     */
    static final int MAX_UNIT_CHARS = 1200;

    /**
     * Zielgröße des optionalen Stimmstabilitäts-Fixes: Nach Erreichen dieser Größe endet das Stück an der nächsten
     * Satzgrenze. Da einzelne Sätze vorher auf {@link #MAX_CHUNK_CHARS} begrenzt werden, bleibt ein Stück deutlich
     * unter dem Bereich um 1000 Zeichen, in dem generative TTS-Modelle beobachtbar instabil werden können.
     */
    static final int VOICE_STABILITY_TARGET_CHARS = 500;

    private static final Pattern FENCE = Pattern.compile("(?s)```.*?(```|$)|~~~.*?(~~~|$)");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    // Match horizontal whitespace only: \s would also swallow the blank line before a heading or list item and
    // merge the paragraphs that PARAGRAPHS chunking relies on. Drop a table rule together with its own line end.
    private static final Pattern LINE_MARKERS = Pattern.compile(
            "(?m)^[ \\t]*(#{1,6}[ \\t]+|>[ \\t]?|[-*+][ \\t]+|\\d+[.)][ \\t]+)");
    private static final Pattern TABLE_RULE = Pattern.compile("(?m)^[ \\t]*\\|?[ \\t]*:?-{3,}[^\\n]*\\n?");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__|\\*|_|~~)");
    private static final Pattern SPEAKABLE = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…:;])\\s+");

    /** Ein Bild, auch als Link verpackt ({@code [![alt](bild)](ziel)}): fällt mitsamt Alternativtext weg. */
    private static final Pattern LINKED_IMAGE = Pattern.compile("\\[\\s*!\\[[^\\]]*\\]\\([^)]*\\)\\s*\\]\\([^)]*\\)");
    /** Bild-Referenzen ({@code ![alt][ref]}) sind ebenfalls nur Darstellung. */
    private static final Pattern REFERENCE_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\[[^\\]]*\\]");
    /** Eingebettetes HTML für Grafiken: Bild, SVG, Bildunterschrift, Canvas, Objekt. */
    private static final Pattern HTML_VISUAL = Pattern.compile(
            "(?is)<(svg|figure|figcaption|canvas|object|picture)\\b.*?</\\1\\s*>|<(img|embed|source)\\b[^>]*>");

    /**
     * Unsichtbare Zeichen, die keine Anzeige zeigt: Format-Zeichen (weiche Trennung, Null-Breiten-Zeichen, Wortverbinder,
     * Byte-Order-Mark, Richtungsmarken) und Variantenselektoren hinter Emojis.
     */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\x{FE00}-\\x{FE0F}]");
    /** Zeilen- und Absatztrenner aus Unicode. */
    private static final Pattern LINE_SEPARATOR = Pattern.compile("[\\x{2028}\\x{2029}]");
    /** Steuerzeichen außer dem Zeilenumbruch. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n]]");
    /** Geschützte, schmale und andere typografische Leerzeichen. */
    private static final Pattern SPACE = Pattern.compile("\\p{Zs}");
    /**
     * Bildzeichen ohne Wortlaut: Pfeile, Rahmenlinien und Blockgrafik, Symbole und Dingbats, Emojis. Eine generative
     * Stimme erfindet für sie Laute oder Zahlen.
     */
    private static final Pattern PICTOGRAPH = Pattern.compile(
            "[\\x{2190}-\\x{21FF}\\x{2500}-\\x{259F}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F000}-\\x{1FAFF}]");

    private SpeechText() {
    }

    /**
     * Lässt nur Zeichen stehen, die eine Stimme sinnvoll sprechen kann, und behält die Absätze: Unicode wird
     * zusammengesetzt (NFC), unsichtbare Zeichen fallen weg, Sonderleerzeichen und Steuerzeichen werden zu Leerzeichen,
     * Bildzeichen (Pfeile, Rahmen, Emojis) zu Leerraum. Läuft vor jeder Synthese, auch bei Merlin.
     */
    public static String speakableCharacters(String text) {
        if (text == null) {
            return "";
        }
        String result = Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
        result = INVISIBLE.matcher(result).replaceAll("");
        result = LINE_SEPARATOR.matcher(result).replaceAll("\n");
        result = CONTROL.matcher(result).replaceAll(" ");
        result = SPACE.matcher(result).replaceAll(" ");
        result = PICTOGRAPH.matcher(result).replaceAll(" ");
        result = result.replaceAll("[ \\t]+", " ").replaceAll(" ?\n ?", "\n").replaceAll(" ([.,!?;:…])", "$1");
        return result.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * Entfernt alles rein Visuelle aus einem Seitentext, bevor er vorgelesen wird (Lesebereich): Bilder samt
     * Alternativtext und Bildunterschrift, eingebettete Grafiken, Diagramm- und Codeblöcke (Mermaid, Draw.io usw.).
     * Überschriften, Absätze, Listen und Tabellen bleiben stehen; {@link #plainText} bereitet das Ergebnis wie im Chat
     * weiter auf.
     */
    public static String withoutVisuals(String markdown) {
        if (markdown == null) {
            return "";
        }
        String text = FENCE.matcher(markdown.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("\n\n");
        text = HTML_VISUAL.matcher(text).replaceAll("");
        text = LINKED_IMAGE.matcher(text).replaceAll("");
        text = IMAGE.matcher(text).replaceAll("");
        text = REFERENCE_IMAGE.matcher(text).replaceAll("");
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** Markdown → vorlesbarer Text (Absätze bleiben durch Leerzeilen getrennt). */
    public static String plainText(String markdown) {
        if (markdown == null) {
            return "";
        }
        String text = FENCE.matcher(markdown.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("\n\n");
        text = IMAGE.matcher(text).replaceAll("$1");
        text = LINK.matcher(text).replaceAll("$1");
        text = URL.matcher(text).replaceAll("");
        text = INLINE_CODE.matcher(text).replaceAll("$1");
        text = TABLE_RULE.matcher(text).replaceAll("");
        text = LINE_MARKERS.matcher(text).replaceAll("");
        text = text.replace('|', ' ');
        text = EMPHASIS.matcher(text).replaceAll("");
        return text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** Ein Stück je Satz; leere Stücke fallen weg. */
    public static List<String> chunks(String plainText) {
        return chunks(plainText, SpeechChunking.SENTENCES);
    }

    /** Stücke in der gewählten Einheit, in Lesereihenfolge; leere fallen weg. */
    public static List<String> chunks(String plainText, SpeechChunking chunking) {
        return chunks(plainText, chunking, false);
    }

    /**
     * Stücke in der gewählten Einheit. Mit Stimmstabilitäts-Fix werden Absatz-/Antwort-Stücke nach Erreichen von
     * ungefähr {@link #VOICE_STABILITY_TARGET_CHARS} Zeichen an der nächsten Satzgrenze beendet.
     */
    static List<String> chunks(String plainText, SpeechChunking chunking, boolean voiceStabilityFix) {
        List<String> chunks = new ArrayList<String>();
        if (plainText == null) {
            return chunks;
        }
        List<String> unit = new ArrayList<String>();
        for (String paragraph : plainText.split("\\n\\s*\\n")) {
            String flat = paragraph.replace('\n', ' ').trim();
            if (flat.isEmpty()) {
                continue;
            }
            List<String> sentences = new ArrayList<String>();
            for (String sentence : SENTENCE_END.split(flat)) {
                addBounded(sentences, sentence.trim());
            }
            if (chunking == SpeechChunking.SENTENCES) {
                chunks.addAll(sentences);
            } else {
                unit.addAll(sentences);
                if (chunking != SpeechChunking.ANSWER) {
                    addUnits(chunks, unit, voiceStabilityFix);
                    unit.clear();
                }
            }
        }
        addUnits(chunks, unit, voiceStabilityFix);
        return chunks;
    }

    /**
     * Ein Stück der Textanalyse, begrenzt auf {@link #MAX_UNIT_CHARS} Zeichen (längere werden an Satzgrenzen geteilt,
     * damit eine Äußerung im Token-Rahmen der Stimme bleibt); ohne sprechbares Zeichen leer.
     */
    static List<String> bounded(String chunk) {
        return bounded(chunk, false);
    }

    /** Wie {@link #bounded(String)}, optional mit dem Stimmstabilitäts-Fix. */
    static List<String> bounded(String chunk, boolean voiceStabilityFix) {
        return chunks(chunk, SpeechChunking.ANSWER, voiceStabilityFix);
    }

    /** Fasst Sätze zu Stücken bis {@link #MAX_UNIT_CHARS} zusammen. */
    static void addUnits(List<String> chunks, List<String> sentences) {
        addUnits(chunks, sentences, false);
    }

    /**
     * Fasst Sätze zusammen. Ohne Fix gilt die harte bisherige Obergrenze; mit Fix wird nach Erreichen der Zielgröße
     * an der gerade erreichten Satzgrenze abgeschlossen. So entstehen typischerweise Stücke um 500 Zeichen statt
     * knapp 1000 oder mehr.
     */
    static void addUnits(List<String> chunks, List<String> sentences, boolean voiceStabilityFix) {
        if (voiceStabilityFix) {
            addStableUnits(chunks, sentences);
            return;
        }
        StringBuilder unit = new StringBuilder();
        for (String sentence : sentences) {
            if (unit.length() > 0 && unit.length() + 1 + sentence.length() > MAX_UNIT_CHARS) {
                chunks.add(unit.toString());
                unit.setLength(0);
            }
            if (unit.length() > 0) {
                unit.append(' ');
            }
            unit.append(sentence);
        }
        if (unit.length() > 0) {
            chunks.add(unit.toString());
        }
    }

    private static void addStableUnits(List<String> chunks, List<String> sentences) {
        StringBuilder unit = new StringBuilder();
        for (String sentence : sentences) {
            if (unit.length() > 0) {
                unit.append(' ');
            }
            unit.append(sentence);
            if (unit.length() >= VOICE_STABILITY_TARGET_CHARS) {
                chunks.add(unit.toString());
                unit.setLength(0);
            }
        }
        if (unit.length() > 0) {
            chunks.add(unit.toString());
        }
    }

    private static void addBounded(List<String> chunks, String sentence) {
        String rest = sentence;
        while (rest.length() > MAX_CHUNK_CHARS) {
            int end;
            int comma = rest.lastIndexOf(", ", MAX_CHUNK_CHARS);
            int space = rest.lastIndexOf(' ', MAX_CHUNK_CHARS);
            if (comma >= MAX_CHUNK_CHARS / 3) {
                end = comma + 1;
            } else if (space >= MAX_CHUNK_CHARS / 3) {
                end = space;
            } else {
                end = MAX_CHUNK_CHARS;
            }
            chunks.add(rest.substring(0, end).trim());
            rest = rest.substring(end).trim();
        }
        if (hasSpeakableCharacter(rest)) {
            chunks.add(rest);
        }
    }

    private static boolean hasSpeakableCharacter(String text) {
        return SPEAKABLE.matcher(text).find();
    }
}
