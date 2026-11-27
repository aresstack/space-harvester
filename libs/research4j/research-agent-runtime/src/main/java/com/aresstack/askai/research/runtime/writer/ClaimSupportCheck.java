package com.aresstack.askai.research.runtime.writer;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 1 of the claim check (plan D8, PR 28): every number, year and percentage and every quotation of a sentence
 * must occur in a passage that the sentence, or its paragraph, cites. Decimal and thousands separators, percent signs
 * and quotation marks are normalised first, so "15,5 %" matches "15.5 percent". A miss becomes an
 * {@code unsupported number} or {@code unsupported quote} flag; nothing is removed. Proper names are not checked,
 * because German capitalises every noun. Stateless and deterministic.
 */
public final class ClaimSupportCheck {

    /** Digits with optional internal separators; the narrow and no-break spaces and the apostrophe group thousands. */
    static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,\u00A0\u202F\u2009']\\d+)*");
    private static final Pattern[] QUOTES = {
            Pattern.compile("\u201E([^\u201C\u201D\"]{2,}?)[\u201C\u201D\"]"), // „…“
            Pattern.compile("\u201C([^\u201D\u201E]{2,}?)\u201D"),             // “…”
            Pattern.compile("\u00AB([^\u00BB]{2,}?)\u00BB"),                   // «…»
            Pattern.compile("\u00BB([^\u00AB]{2,}?)\u00AB"),                   // »…«
            Pattern.compile("\"([^\"]{2,}?)\"")                                // "…"
    };
    /** Quotations of a single word are emphasis ("so-called"), not quotes of a source. */
    static final int MIN_QUOTE_WORDS = 2;

    /**
     * @param result   the validated answer
     * @param passages the evidence pack, by local number
     * @return the unsupported-number and unsupported-quote flags, in text order
     */
    public List<ClaimFlag> check(CitationValidator.Result result, List<EvidencePassage> passages) {
        Map<Integer, Set<String>> numbers = new HashMap<Integer, Set<String>>();
        Map<Integer, String> normalised = new HashMap<Integer, String>();
        for (EvidencePassage passage : passages) {
            numbers.put(passage.getNumber(), numbersOf(passage.getText()));
            normalised.put(passage.getNumber(), normaliseText(passage.getText()));
        }
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        List<CitationValidator.Paragraph> paragraphs = result.getParagraphs();
        for (int p = 0; p < paragraphs.size(); p++) {
            CitationValidator.Paragraph paragraph = paragraphs.get(p);
            StringBuilder paragraphPlain = new StringBuilder();
            for (CitationValidator.Sentence sentence : paragraph.getSentences()) {
                Set<Integer> cited = new LinkedHashSet<Integer>(sentence.getCited());
                cited.addAll(paragraph.getCited());
                String plain = sentence.plainText();
                paragraphPlain.append(paragraphPlain.length() == 0 ? "" : " ").append(plain);
                Matcher m = NUMBER.matcher(plain);
                while (m.find()) {
                    if (!supported(readings(m.group()), unitAfter(plain, m.end()), cited, numbers)) {
                        flags.add(new ClaimFlag(ClaimFlag.Kind.UNSUPPORTED_NUMBER, p, sentence.getText(),
                                numberWithUnit(plain, m)));
                    }
                }
            }
            // Quotations are found in the whole paragraph: a quote of several sentences spans the sentence split.
            for (String quote : quotesOf(paragraphPlain.toString())) {
                String needle = normaliseText(quote);
                if (needle.split(" ").length < MIN_QUOTE_WORDS) {
                    continue;
                }
                boolean found = false;
                for (Integer n : paragraph.getCited()) {
                    String hay = normalised.get(n);
                    if (hay != null && (" " + hay + " ").contains(" " + needle + " ")) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    flags.add(new ClaimFlag(ClaimFlag.Kind.UNSUPPORTED_QUOTE, p, sentenceOf(paragraph, quote), quote));
                }
            }
        }
        return flags;
    }

    /** The sentence a quotation starts in, for the flag; the paragraph when it spans several. */
    private static String sentenceOf(CitationValidator.Paragraph paragraph, String quote) {
        for (CitationValidator.Sentence s : paragraph.getSentences()) {
            if (s.getText().contains(quote)) {
                return s.getText();
            }
        }
        return paragraph.text();
    }

    private static boolean supported(Set<String> readings, String unit, Set<Integer> cited,
                                     Map<Integer, Set<String>> numbers) {
        for (Integer n : cited) {
            Set<String> known = numbers.get(n);
            if (known == null) {
                continue;
            }
            for (String reading : readings) {
                if (known.contains(unit.isEmpty() ? reading : reading + "|" + unit)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Every reading of every number token of a text, bare ({@code 15}) and, when a known unit follows, with its
     * canonical unit ({@code 15|kg}).
     */
    static Set<String> numbersOf(String text) {
        Set<String> all = new LinkedHashSet<String>();
        String t = text == null ? "" : text;
        Matcher m = NUMBER.matcher(t);
        while (m.find()) {
            String unit = unitAfter(t, m.end());
            for (String reading : readings(m.group())) {
                all.add(reading);
                if (!unit.isEmpty()) {
                    all.add(reading + "|" + unit);
                }
            }
        }
        return all;
    }

    private static final Pattern UNIT_WORD = Pattern.compile("\\G[\\s\u00A0\u202F]?(%|°\\s?[CF]|[€$£]|\\p{L}{1,12}\\.?)");
    private static final Map<String, String> UNITS = new HashMap<String, String>();

    static {
        unit("%", "%", "percent", "per cent", "prozent", "pct", "vh");
        unit("kg", "kg", "kilogram", "kilograms", "kilogramm");
        unit("g", "g", "gram", "grams", "gramm");
        unit("t", "t", "tonne", "tonnes", "ton", "tons", "tonnen");
        unit("km", "km", "kilometre", "kilometres", "kilometer", "kilometers");
        unit("m", "m", "metre", "metres", "meter", "meters");
        unit("cm", "cm", "centimetre", "centimetres", "zentimeter");
        unit("mm", "mm", "millimetre", "millimetres", "millimeter");
        unit("l", "l", "litre", "litres", "liter", "liters");
        unit("kwh", "kwh", "mwh", "gwh", "twh");
        unit("kw", "kw");
        unit("mw", "mw", "megawatt", "megawatts");
        unit("gw", "gw", "gigawatt", "gigawatts");
        unit("°c", "°c", "°", "grad");
        unit("€", "€", "eur", "euro", "euros");
        unit("$", "$", "usd", "dollar", "dollars");
        unit("h", "h", "hour", "hours", "stunde", "stunden");
        unit("min", "min", "minute", "minutes", "minuten");
        unit("year", "year", "years", "jahr", "jahre", "jahren");
        unit("million", "million", "millionen", "mio", "mio.", "mn");
        unit("billion", "billion", "milliarde", "milliarden", "mrd", "mrd.", "bn");
    }

    private static void unit(String canonical, String... aliases) {
        for (String alias : aliases) {
            UNITS.put(alias, canonical);
        }
    }

    /** The canonical unit right after a number ("15 kg", "15,5 %", "15 percent"); empty when none is known. */
    static String unitAfter(String text, int end) {
        Matcher unit = UNIT_WORD.matcher(text);
        if (!unit.find(end)) {
            return "";
        }
        String word = unit.group(1).toLowerCase(Locale.ROOT).replace(" ", "");
        if ("kwh".equals(word) || "mwh".equals(word) || "gwh".equals(word) || "twh".equals(word)) {
            return word; // energy units differ by their prefix; listed together only to be recognised
        }
        String canonical = UNITS.get(word);
        if (canonical == null && word.endsWith(".")) {
            canonical = UNITS.get(word.substring(0, word.length() - 1));
        }
        if (canonical == null && word.startsWith("per")) {
            return "";
        }
        return canonical == null ? "" : canonical;
    }

    /**
     * The canonical values a number token can mean. "15,5" and "15.5" both read 15.5; "1.500" and "1,500" read
     * 1500 or 1.5, because one separator before exactly three digits is ambiguous between German and English;
     * "1.234,5" reads 1234.5.
     */
    static Set<String> readings(String token) {
        Set<String> out = new LinkedHashSet<String>();
        String t = token.replaceAll("[\u00A0\u202F\u2009']", "");
        int dots = count(t, '.');
        int commas = count(t, ',');
        if (dots == 0 && commas == 0) {
            out.add(canonical(t));
        } else if (dots > 0 && commas > 0) {
            char decimal = t.lastIndexOf('.') > t.lastIndexOf(',') ? '.' : ',';
            char thousands = decimal == '.' ? ',' : '.';
            out.add(canonical(t.replace(String.valueOf(thousands), "").replace(decimal, '.')));
        } else if (dots + commas > 1) {
            out.add(canonical(t.replaceAll("[.,]", "")));
        } else {
            int at = Math.max(t.indexOf('.'), t.indexOf(','));
            String tail = t.substring(at + 1);
            out.add(canonical(t.substring(0, at) + "." + tail));
            if (tail.length() == 3) {
                out.add(canonical(t.substring(0, at) + tail));
            }
        }
        out.remove("");
        return out;
    }

    private static String canonical(String plain) {
        try {
            BigDecimal value = new BigDecimal(plain);
            return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** The token with a directly following percent sign or short unit word, for a readable flag. */
    private static String numberWithUnit(String sentence, Matcher m) {
        Matcher unit = Pattern.compile("\\G\\s?(%|\\p{L}{1,12})").matcher(sentence);
        if (unit.find(m.end())) {
            return m.group() + (unit.group().startsWith(" ") ? " " : "") + unit.group(1);
        }
        return m.group();
    }

    static List<String> quotesOf(String sentence) {
        List<String> quotes = new ArrayList<String>();
        String rest = sentence;
        for (Pattern pattern : QUOTES) {
            Matcher m = pattern.matcher(rest);
            StringBuffer remaining = new StringBuffer();
            while (m.find()) {
                quotes.add(m.group(1).trim());
                m.appendReplacement(remaining, " ");
            }
            m.appendTail(remaining);
            rest = remaining.toString();
        }
        return quotes;
    }

    /** Lower case, accents kept, every run of non-letters and non-digits one space; quote styles do not matter. */
    static String normaliseText(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
    }
}
