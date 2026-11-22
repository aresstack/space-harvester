package com.aresstack.spaceharvester.application.podcast;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Macht den Text eines Beitrags sprechbar, bevor er an die Sprachausgabe geht (Angelo 22.11.2026: „Die Sprachausgabe
 * verhaspelt sich immer bei den Bindestrichen … und gibt dann teilweise ganz auf“). Nur für das Audio: Bericht und
 * Transkript zeigen den Text, wie das Modell ihn schrieb.
 *
 * <p>Die Schritte, in dieser Reihenfolge:
 * <ul>
 *   <li>typografische Bindestriche (geschützter Bindestrich U+2011, U+2010, Minuszeichen) werden zum einfachen;
 *   weiche Trennstriche fallen weg;</li>
 *   <li>Datumsangaben 2026-11-20 werden zu 20.11.2026, Zahlenbereiche 801–805 zu „801 bis 805“, Gedankenstriche zu
 *   einer Pause (Komma), Pfeile zu „dann“;</li>
 *   <li>Bindestriche in Wörtern („3-D-Datenarchiv“, „1-zu-1-Test“) werden zu Leerzeichen, ein Ergänzungsstrich
 *   („Gelände- und Gewässerdaten“) fällt weg: Gesprochen ist ein Bindestrich stumm;</li>
 *   <li>Pfade (/etc/sudoers.d) und Schrägstriche werden zu Leerzeichen, Hexadezimalzahlen (0x2080) zu „hexadezimal
 *   2080“, Tausender mit Leerraum (8 100) zu einer Zahl, „&amp;“ zu „und“, „%“ zu „Prozent“;</li>
 *   <li>gängige Abkürzungen werden ausgeschrieben (z. B., d. h., bzw., ca., usw., Nr.), Kürzel, die die Stimme als Wort
 *   läse, buchstabiert (BSCW).</li>
 * </ul>
 */
final class SpeakableText {

    private static final Pattern SOFT_HYPHEN = Pattern.compile("\\x{00AD}");
    private static final Pattern TYPOGRAPHIC_HYPHEN = Pattern.compile("[\\x{2010}\\x{2011}\\x{2212}\\x{FE63}\\x{FF0D}]");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern NUMBER_RANGE = Pattern.compile("(\\d)\\s*[-\\x{2012}\\x{2013}\\x{2014}]\\s*(\\d)");
    private static final Pattern DASH = Pattern.compile("\\s*[\\x{2012}\\x{2013}\\x{2014}\\x{2015}]\\s*|\\s+--?\\s+");
    private static final Pattern ARROW = Pattern.compile("\\s*(?:[\\x{2190}-\\x{21FF}\\x{27F5}-\\x{27FF}]|->|=>)\\s*");
    /** Ergänzungsstrich vor „und“, „oder“, „bis“, „sowie“, Komma: „Gelände- und Gewässerdaten“. */
    private static final Pattern SUSPENDED = Pattern.compile("(\\p{L})-(?=\\s*(?:,|und\\b|oder\\b|bis\\b|sowie\\b))");
    /** Bindestrich zwischen zwei Wortteilen aus Buchstaben oder Ziffern. */
    private static final Pattern WORD_HYPHEN = Pattern.compile("(?<=[\\p{L}\\p{N}])-+(?=[\\p{L}\\p{N}])");
    private static final Pattern HEX = Pattern.compile("\\b0[xX]([0-9A-Fa-f]+)\\b");
    /**
     * Tausender mit Leerraum: mit geschütztem oder schmalem Leerzeichen immer, mit einfachem nur nach ein oder zwei
     * Ziffern („8 100“), damit zwei Nummern wie „801 802“ nicht zusammenwachsen.
     */
    private static final Pattern THOUSANDS = Pattern.compile(
            "\\b\\d{1,3}(?:[\\x{00A0}\\x{202F}\\x{2009}]\\d{3})+\\b|\\b\\d{1,2}(?: \\d{3})+\\b");
    private static final Pattern THOUSAND_GROUP = Pattern.compile("[ \\x{00A0}\\x{202F}\\x{2009}](?=\\d{3})");
    private static final Pattern SLASH = Pattern.compile("/+");
    private static final Pattern UNDERSCORE = Pattern.compile("_+");
    private static final Pattern PERCENT = Pattern.compile("(\\d)\\s*%");
    private static final Pattern AMPERSAND = Pattern.compile("\\s*&\\s*");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile(" ([,.!?;:])");
    private static final Pattern DOUBLE_COMMA = Pattern.compile(",\\s*,+");

    /** Abkürzungen, die die Stimme sonst Buchstabe für Buchstabe oder gar nicht liest. */
    private static final Map<Pattern, String> ABBREVIATIONS = Map.of(
            Pattern.compile("\\b[zZ]\\.\\s?[bB]\\."), "zum Beispiel",
            Pattern.compile("\\b[dD]\\.\\s?[hH]\\."), "das heißt",
            Pattern.compile("\\bbzw\\."), "beziehungsweise",
            Pattern.compile("\\bca\\.(?=\\s)"), "circa",
            Pattern.compile("\\busw\\."), "und so weiter",
            Pattern.compile("\\bu\\.\\s?a\\."), "unter anderem",
            Pattern.compile("\\bNr\\.(?=\\s)"), "Nummer",
            Pattern.compile("\\bggf\\."), "gegebenenfalls",
            Pattern.compile("\\binkl\\."), "inklusive");

    /** Kürzel, die die Stimme als Wort läse; ausgeschrieben, wie man sie spricht. */
    private static final Map<Pattern, String> SPELLED = Map.of(
            Pattern.compile("\\bBSCW\\b"), "Be Es Ze We");

    private SpeakableText() {
    }

    /** Der Text, wie ihn die Sprachausgabe bekommt; nie {@code null}. */
    static String of(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String result = SOFT_HYPHEN.matcher(text).replaceAll("");
        result = TYPOGRAPHIC_HYPHEN.matcher(result).replaceAll("-");
        result = ISO_DATE.matcher(result).replaceAll("$3.$2.$1");
        result = NUMBER_RANGE.matcher(result).replaceAll("$1 bis $2");
        result = ARROW.matcher(result).replaceAll(", dann ");
        result = DASH.matcher(result).replaceAll(", ");
        result = HEX.matcher(result).replaceAll("hexadezimal $1");
        result = joinThousands(result);
        for (Map.Entry<Pattern, String> abbreviation : ABBREVIATIONS.entrySet()) {
            result = abbreviation.getKey().matcher(result).replaceAll(abbreviation.getValue());
        }
        result = SUSPENDED.matcher(result).replaceAll("$1");
        result = WORD_HYPHEN.matcher(result).replaceAll(" ");
        result = SLASH.matcher(result).replaceAll(" ");
        result = UNDERSCORE.matcher(result).replaceAll(" ");
        result = PERCENT.matcher(result).replaceAll("$1 Prozent");
        result = AMPERSAND.matcher(result).replaceAll(" und ");
        for (Map.Entry<Pattern, String> spelled : SPELLED.entrySet()) {
            result = spelled.getKey().matcher(result).replaceAll(spelled.getValue());
        }
        result = DOUBLE_COMMA.matcher(result).replaceAll(",");
        result = SPACES.matcher(result).replaceAll(" ");
        result = SPACE_BEFORE_PUNCTUATION.matcher(result).replaceAll("$1");
        result = result.strip();
        while (result.startsWith(",")) {
            result = result.substring(1).strip();
        }
        return result;
    }

    private static String joinThousands(String text) {
        Matcher matcher = THOUSANDS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    THOUSAND_GROUP.matcher(matcher.group()).replaceAll("")));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
