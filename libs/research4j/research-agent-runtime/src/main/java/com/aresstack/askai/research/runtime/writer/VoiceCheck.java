package com.aresstack.askai.research.runtime.writer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds sentences in the first person plural ("wir", "unsere", "we", "our"). A book written from sources has no "we"
 * of its own: the word belongs to a passage's author, as in the manufacturer's "unsere tragbaren Geräte" and
 * "implementieren wir" that live run 4 carried into books 2 and 3. A quotation keeps its author's voice, so quoted
 * text is left out; a quotation can run over several sentences, so its quotation marks are paired across the whole
 * paragraph. German and English pronouns; "US" in capitals is the country. Stateless and deterministic, no model
 * call.
 */
public final class VoiceCheck {

    /** wir, uns, unser, unsere, unserem, unseren, unserer, unseres, we, us, our, ours (first letter in any case). */
    private static final Pattern FIRST_PERSON_PLURAL = Pattern.compile(
            "(?<![\\p{L}\\p{Nd}])(?:[Ww]ir|[Uu]ns(?:er(?:e[mnrs]?)?)?|[Ww]e|[Uu]s|[Oo]urs?)(?![\\p{L}\\p{Nd}])");
    /** Quoted text in the quotation marks a German or English book uses. */
    private static final Pattern QUOTED = Pattern.compile(
            "\"[^\"]*\"|„[^“”\"]*[“”\"]|“[^”]*”|«[^»]*»|»[^«]*«|‚[^‘’]*[‘’]");

    /** The first-person word a sentence uses outside its quotations, or "" when it has none. */
    public String firstPersonWord(String sentence) {
        return firstPersonWords(Collections.singletonList(sentence)).get(0);
    }

    /**
     * The first-person word of each sentence of one paragraph, outside the paragraph's quotations, or "" for a
     * sentence without one: one entry per sentence, in order. The sentences are read as one paragraph, joined by a
     * space, so a quotation that opens in one sentence and closes in a later one covers both.
     */
    public List<String> firstPersonWords(List<String> sentences) {
        StringBuilder paragraph = new StringBuilder();
        int[] starts = new int[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            if (i > 0) {
                paragraph.append(' ');
            }
            starts[i] = paragraph.length();
            paragraph.append(sentences.get(i) == null ? "" : sentences.get(i));
        }
        char[] text = paragraph.toString().toCharArray();
        blank(CitationValidator.MARKER, text);
        blank(QUOTED, text);
        List<String> words = new ArrayList<String>(Collections.nCopies(sentences.size(), ""));
        Matcher m = FIRST_PERSON_PLURAL.matcher(new String(text));
        while (m.find()) {
            int sentence = sentenceAt(starts, m.start());
            if (words.get(sentence).isEmpty()) {
                words.set(sentence, m.group());
            }
        }
        return words;
    }

    /** One {@link ClaimFlag.Kind#FIRST_PERSON_VOICE} flag per sentence of the answer that speaks as "we". */
    public List<ClaimFlag> check(CitationValidator.Result result) {
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        List<CitationValidator.Paragraph> paragraphs = result.getParagraphs();
        for (int p = 0; p < paragraphs.size(); p++) {
            List<String> sentences = new ArrayList<String>();
            for (CitationValidator.Sentence sentence : paragraphs.get(p).getSentences()) {
                sentences.add(sentence.getText());
            }
            List<String> words = firstPersonWords(sentences);
            for (int i = 0; i < sentences.size(); i++) {
                if (!words.get(i).isEmpty()) {
                    flags.add(new ClaimFlag(ClaimFlag.Kind.FIRST_PERSON_VOICE, p, sentences.get(i), words.get(i)));
                }
            }
        }
        return flags;
    }

    /** Overwrites every match with spaces, so the offsets stay those of the paragraph. */
    private static void blank(Pattern pattern, char[] text) {
        Matcher m = pattern.matcher(new String(text));
        while (m.find()) {
            Arrays.fill(text, m.start(), m.end(), ' ');
        }
    }

    /** The index of the sentence the offset falls into. */
    private static int sentenceAt(int[] starts, int offset) {
        int sentence = 0;
        while (sentence + 1 < starts.length && starts[sentence + 1] <= offset) {
            sentence++;
        }
        return sentence;
    }
}
