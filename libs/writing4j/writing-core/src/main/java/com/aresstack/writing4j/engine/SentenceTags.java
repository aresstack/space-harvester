package com.aresstack.writing4j.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sentence tags of {@link CitationFormat#SENTENCE_TAGS}: every sentence of every passage gets an 8-character
 * lowercase hex tag (the first 8 hex digits of the MD5 of passage number, sentence index and sentence, as the SUI-1
 * model card preprocesses its input), written as {@code <tag>sentence</tag>} with no whitespace between tags. The
 * writer cites {@code [<tag>]}; {@link #toNumbered} turns those citations into the {@code [n]} markers of the citation
 * check, so a tag the pack does not hold is reported like an unknown number. Immutable.
 */
public final class SentenceTags {

    /** The length of a tag in hex digits (model card: 8 lowercase hex characters). */
    public static final int TAG_LENGTH = 8;

    /** A sentence ends at . ! ? (optionally a closing quote or bracket) followed by whitespace. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?][\"“”»’')]?)\\s+");
    /** A short abbreviation ("z.", "B.", "ca.", "vgl.") does not end a sentence. */
    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?:^|\\s)(?:\\p{L}|ca|dr|nr|vgl|bzw|usw|etc|prof|abb|kap|mio|mrd|jh|evtl|ggf|inkl|tab|e\\.g|i\\.e|vs)\\.$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** One citation group: {@code [<a1b2c3d4>]}, {@code [<a1b2c3d4>, <e5f6a7b8>]}, also without angle brackets. */
    static final Pattern CITATION = Pattern.compile(
            "\\[\\s*<?/?[0-9a-fA-F]{8}>?(?:\\s*[,;]?\\s*<?/?[0-9a-fA-F]{8}>?)*\\s*\\]");
    private static final Pattern TAG = Pattern.compile("[0-9a-fA-F]{8}");
    /** Tags the writer copied into its prose instead of citing ({@code <a1b2c3d4>}, {@code </a1b2c3d4>}). */
    private static final Pattern STRAY_TAG = Pattern.compile("</?[0-9a-fA-F]{8}>");
    /** The same passage cited twice in a row after the mapping: {@code [3] [3]}. */
    private static final Pattern REPEATED_MARKER = Pattern.compile("\\[(\\d{1,3})\\](\\s*\\[\\1\\])+");

    private final Map<Integer, List<String[]>> sentencesByPassage;
    private final Map<String, Integer> passageOfTag;

    private SentenceTags(Map<Integer, List<String[]>> sentencesByPassage, Map<String, Integer> passageOfTag) {
        this.sentencesByPassage = sentencesByPassage;
        this.passageOfTag = passageOfTag;
    }

    /** The tags of a pack's passages. */
    public static SentenceTags of(List<EvidencePassage> passages) {
        Map<Integer, List<String[]>> sentences = new LinkedHashMap<Integer, List<String[]>>();
        Map<String, Integer> passageOfTag = new LinkedHashMap<String, Integer>();
        for (EvidencePassage passage : passages) {
            List<String[]> tagged = new ArrayList<String[]>();
            int index = 0;
            for (String sentence : sentences(passage.getText())) {
                String tag = tag(passage.getNumber(), index++, sentence, passageOfTag.keySet());
                passageOfTag.put(tag, passage.getNumber());
                tagged.add(new String[]{tag, sentence});
            }
            sentences.put(passage.getNumber(), Collections.unmodifiableList(tagged));
        }
        return new SentenceTags(Collections.unmodifiableMap(sentences), Collections.unmodifiableMap(passageOfTag));
    }

    /** A passage's text as tagged sentences, ready for its {@code <text>} block. */
    public String tagged(int passageNumber) {
        List<String[]> sentences = sentencesByPassage.get(passageNumber);
        if (sentences == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String[] s : sentences) {
            sb.append('<').append(s[0]).append('>').append(s[1]).append("</").append(s[0]).append('>');
        }
        return sb.toString();
    }

    /** The passage a tag belongs to, or null. */
    public Integer passageOf(String tag) {
        return tag == null ? null : passageOfTag.get(tag.toLowerCase(java.util.Locale.ROOT));
    }

    /** How many tags the pack holds (the "number of XML tags" of the SUI-1 prompt). */
    public int size() {
        return passageOfTag.size();
    }

    /**
     * The answer with every tag citation replaced by the numbers of the cited passages ({@code [<a1b2c3d4>]} becomes
     * {@code [3]}); a tag of no passage becomes the number 0, which names no passage, so the citation check strips and
     * reports it. Tags copied into the prose are removed, and a passage cited twice in a row is cited once.
     */
    public String toNumbered(String answer) {
        if (answer == null || answer.isEmpty()) {
            return answer == null ? "" : answer;
        }
        Matcher m = CITATION.matcher(answer);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Set<Integer> numbers = new LinkedHashSet<Integer>();
            Matcher tag = TAG.matcher(m.group());
            while (tag.find()) {
                Integer n = passageOf(tag.group());
                numbers.add(n == null ? 0 : n);
            }
            StringBuilder marker = new StringBuilder("[");
            for (Integer n : numbers) {
                if (marker.length() > 1) {
                    marker.append(", ");
                }
                marker.append(n);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(marker.append(']').toString()));
        }
        m.appendTail(sb);
        String numbered = STRAY_TAG.matcher(sb.toString()).replaceAll("");
        return REPEATED_MARKER.matcher(numbered).replaceAll("[$1]");
    }

    /** The sentences of a passage, split at sentence ends but not after a short abbreviation. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<String>();
        String clean = text == null ? "" : text.replace('\n', ' ').trim();
        if (clean.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String part : SENTENCE_END.split(clean)) {
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(part.trim());
            if (!ABBREVIATION.matcher(current).find()) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static String tag(int passage, int index, String sentence, Set<String> taken) {
        String seed = passage + ":" + index + ":" + sentence;
        String tag = md5(seed).substring(0, TAG_LENGTH);
        int salt = 0;
        while (taken.contains(tag)) {
            tag = md5(seed + "#" + (++salt)).substring(0, TAG_LENGTH);
        }
        return tag;
    }

    private static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
