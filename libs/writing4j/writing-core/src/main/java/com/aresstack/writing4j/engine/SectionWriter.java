package com.aresstack.writing4j.engine;

import com.aresstack.knowledge4j.model.ModelPort;
import com.aresstack.writing4j.engine.WritingSlots;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.PortMainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The section writer of the Document phase (plan D8, PR 28). It runs on the {@code phase.draft.writer} slot (which
 * inherits the model of {@code phase.draft}), asks for plain text with {@code [n]} markers and checks the answer in
 * code: {@link CitationValidator} strips unknown markers, flags uncited sentences and paragraphs, parses gaps and
 * takes label lines, repeated sentences (also those of the section's earlier parts and of every other written
 * section of the manuscript, word for word or nearly: {@link RepeatCheck}) and question-only paragraphs out of the
 * prose; {@link ClaimSupportCheck} flags numbers and quotations the cited passages do not contain;
 * {@link ClaimCarryCheck} (stage 2) flags sentences whose cited passages are no prose or share too few of their
 * words; {@link VoiceCheck} flags sentences that speak with a passage author's "we".
 *
 * <p>A cut-off answer gets exactly one continuation from its last full sentence; an answer with uncited sentences or
 * unsupported numbers or quotes gets exactly one repair, which sends the error list and never the failed answer. The
 * repaired answer is stored with whatever flags remain, unless it keeps fewer words in sentences without an error
 * than the first answer: then the first answer stays (live run 4: repairs cut parts from 97 words to none and from 123
 * to 42). A section longer than the word target of one call is split by its key points, one call per part, and each
 * part gets the passages of the pack that cover its key points best, those no earlier part took first, numbered
 * {@code [1]..[m]} in its prompt and renumbered to the pack's numbers in the section (live run 4: three parts of one
 * section saw the same eight passages, and up to 13 repeated sentences per part had to be taken out).
 * {@code WriterWorker} stays for the orchestrated path until PR 37.</p>
 *
 * <p>An open point names what the passages lack, never the writing itself (live run 4, book 5): a {@code GAP:} line
 * that names the call's word target ("Zielwert von 1100 Wörtern"), and a {@code GAP:} line of a repaired answer that
 * repeats a sentence the repair listed (the writer moved its uncited sentences there), are dropped like the gap lines
 * that repeat the text ({@link CitationValidator}). One log line per answer gives its words and errors, so a live run
 * shows what each call and each repair delivered.</p>
 */
public final class SectionWriter {

    static final double TEMPERATURE = 0.3;
    private static final String CLOSERS = "\"'\u201C\u201D\u2019\u00BB\u00AB)";

    private final MainModelChat chat;
    private final CitationValidator validator = new CitationValidator();
    private final ClaimSupportCheck claims = new ClaimSupportCheck();
    private final VoiceCheck voice = new VoiceCheck();
    private final Consumer<String> log;
    private final SentenceSimilarity similarity;

    /**
     * @param chat the transport of the {@code phase.draft.writer} slot ({@code WritingSlots.PHASE_DRAFT_WRITER})
     * @param log  receives one line per stripped marker and per extra call; never source text
     */
    public SectionWriter(MainModelChat chat, Consumer<String> log) {
        this(chat, log, null);
    }

    private SectionWriter(MainModelChat chat, Consumer<String> log, SentenceVectors sentences) {
        if (chat == null) {
            throw new IllegalArgumentException("chat must not be null");
        }
        this.chat = chat;
        this.log = log == null ? new Consumer<String>() {
            public void accept(String line) {
            }
        } : log;
        this.similarity = sentences == null ? SentenceSimilarity.none() : new SentenceSimilarity(sentences, this.log);
    }

    /**
     * The same writer with the sentence-embedding slot: the repeat check also takes out paraphrases of sentences the
     * book has ({@link RepeatCheck.Criteria#getMinSimilarity()}), and claim-check stage 2 counts a cited passage whose
     * sentence says the same in other words as carrying ({@link ClaimCarryCheck.Criteria#getMinSimilarity()}).
     */
    public SectionWriter withSentenceVectors(SentenceVectors sentences) {
        return new SectionWriter(chat, log, sentences);
    }

    /** A writer on the host's model port, bound to {@code phase.draft.writer} (falls back to {@code phase.draft}). */
    public static SectionWriter onPort(ModelPort port, Duration timeout, Consumer<String> log) {
        return new SectionWriter(new PortMainModelChat(port, WritingSlots.PHASE_DRAFT_WRITER, timeout), log);
    }

    /** Writes the whole section; more words than one call's target are split by key points. */
    public SectionDraft write(SectionAssignment assignment) {
        List<SectionAssignment> parts = parts(assignment);
        // what the book already says: every other written section; each part adds its own sentences
        java.util.Set<String> written = new java.util.HashSet<String>();
        for (String text : assignment.getWrittenSections()) {
            written.addAll(CitationValidator.sentenceKeys(text));
        }
        RepeatCheck.Memory book = new RepeatCheck(assignment.getRepeats(), assignment.getCarry(), similarity)
                .memory(assignment.getWrittenSections());
        if (!assignment.getWrittenSections().isEmpty()) {
            log.accept("section " + assignment.getSectionId() + ": repeat check against " + book.size()
                    + " sentences of " + assignment.getWrittenSections().size() + " other written sections");
        }
        if (parts.size() == 1 && consecutive(parts.get(0).getPassages())) {
            return writePart(parts.get(0), written, book);
        }
        StringBuilder text = new StringBuilder();
        Map<Integer, EvidencePassage> citations = new LinkedHashMap<Integer, EvidencePassage>();
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        List<String> gaps = new ArrayList<String>();
        List<String> stripped = new ArrayList<String>();
        String hash = "";
        String model = "";
        int calls = 0;
        int words = 0;
        int paragraphOffset = 0;
        String digest = assignment.getPreviousDigest();
        PartContextCriteria context = assignment.getPartContextCriteria();
        for (int index = 0; index < parts.size(); index++) {
            SectionAssignment part = parts.get(index);
            List<EvidencePassage> packed = part.getPassages();
            // with the part context, a later part reads the section's text so far instead of the last sentences
            SectionAssignment call = part.part(part.getKeyPoints(), numbered(packed), part.getWordsPerCall(),
                    context.isEnabled() && index > 0 ? "" : digest);
            if (context.isEnabled()) {
                call = call.inSection(new SectionAssignment.PartContext(index, parts.size(),
                        planLines(parts, index),
                        soFar(text.toString(), context.getSoFarChars())));
            }
            SectionDraft draft = writePart(call, written, book);
            calls += draft.getCalls();
            if (hash.isEmpty()) {
                hash = draft.getPromptHash();
            }
            if (!draft.isOk()) {
                return SectionDraft.failed(draft.getFailure(), hash, calls);
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(renumber(draft.getText(), packed));
            for (Map.Entry<Integer, EvidencePassage> cited : draft.getCitations().entrySet()) {
                EvidencePassage passage = cited.getKey() >= 1 && cited.getKey() <= packed.size()
                        ? packed.get(cited.getKey() - 1) : cited.getValue();
                citations.put(passage.getNumber(), passage);
            }
            for (ClaimFlag f : draft.getFlags()) {
                flags.add(new ClaimFlag(f.getKind(), f.getParagraph() + paragraphOffset,
                        renumber(f.getSentence(), packed), renumber(f.getDetail(), packed)));
            }
            paragraphOffset += draft.getText().isEmpty() ? 0 : draft.getText().split("\n\n").length;
            gaps.addAll(draft.getGaps());
            stripped.addAll(draft.getStrippedMarkers());
            model = draft.getModelLabel().isEmpty() ? model : draft.getModelLabel();
            words += draft.getWordCount();
            digest = lastSentences(draft.getText());
            written.addAll(CitationValidator.sentenceKeys(draft.getText()));
            book.rememberText(draft.getText());
        }
        return new SectionDraft(true, "", text.toString(), new java.util.TreeMap<Integer, EvidencePassage>(citations),
                flags, gaps, stripped, model, hash, calls, words);
    }

    /**
     * The plan line of every key point of the section as part {@code index} sees it: written by an earlier part,
     * this part's, or a later part's. The state follows the part a point belongs to, not its text, so a key point
     * that appears twice keeps the state of each of its parts.
     */
    static List<String> planLines(List<SectionAssignment> parts, int index) {
        List<String> lines = new ArrayList<String>();
        for (int n = 0; n < parts.size(); n++) {
            String state = n < index ? "already written" : n > index ? "a later part" : "this part";
            for (String point : parts.get(n).getKeyPoints()) {
                lines.add(point + " - " + state);
            }
        }
        return lines;
    }

    /**
     * The text of the earlier parts as a part call carries it: without markers (each part numbers its own passages),
     * and when it is longer than {@code maxChars}, its end from a paragraph or sentence start on.
     */
    static String soFar(String text, int maxChars) {
        String plain = SectionWriterPrompt.withoutMarkers(text == null ? "" : text);
        if (maxChars <= 0 || plain.length() <= maxChars) {
            return plain;
        }
        String tail = plain.substring(plain.length() - maxChars);
        int paragraph = tail.indexOf("\n\n");
        if (paragraph >= 0 && paragraph < maxChars / 2) {
            return "[...]\n\n" + tail.substring(paragraph + 2).trim();
        }
        java.util.regex.Matcher sentence = java.util.regex.Pattern.compile("[.!?]\\s+").matcher(tail);
        return "[...] " + (sentence.find() ? tail.substring(sentence.end()) : tail).trim();
    }

    /** One call's worth: first answer, at most one continuation, at most one repair (with its own continuation). */
    SectionDraft writePart(SectionAssignment a) {
        return writePart(a, java.util.Collections.<String>emptySet(), RepeatCheck.none());
    }

    /**
     * @param written the sentence keys of the book's other sections and of the section's earlier parts; this part
     *                leaves out what they say
     * @param book    the same sentences for the near-repeat check; not changed
     */
    SectionDraft writePart(SectionAssignment a, java.util.Set<String> written, RepeatCheck.Memory book) {
        List<ChatMessage> first = SectionWriterPrompt.first(a);
        String hash = SectionWriterPrompt.hash(first);
        int[] calls = {0};
        String[] model = {""};
        String answer = answer(a, first, calls, model);
        if (answer == null) {
            return SectionDraft.failed(lastFailure, hash, calls[0]);
        }
        Checked checked = check(answer, a, written, book);
        List<ClaimFlag> errors = checked.repairable();
        log.accept("section " + a.getSectionId() + ": answer of " + checked.result.wordCount() + " words (target "
                + a.getWordsPerCall() + "), " + errors.size() + " citation errors");
        if (!errors.isEmpty()) {
            log.accept("section " + a.getSectionId() + ": one repair for " + errors.size() + " citation errors");
            String repaired = answer(a, SectionWriterPrompt.repair(a, errors), calls, model);
            if (repaired != null) {
                final Set<String> listed = new HashSet<String>();
                for (ClaimFlag error : errors) {
                    listed.add(CitationValidator.pointKey(error.getSentence()));
                }
                Checked again = check(repaired, a, written, book);
                Checked fixed = new Checked(again.result.withoutGaps(new java.util.function.Predicate<String>() {
                    public boolean test(String point) {
                        return listed.contains(CitationValidator.pointKey(point));
                    }
                }), again.unsupported);
                log.accept("section " + a.getSectionId() + ": repaired answer of " + fixed.result.wordCount()
                        + " words, " + fixed.repairable().size() + " citation errors");
                int before = supportedWords(checked.result, errors);
                int after = supportedWords(fixed.result, fixed.repairable());
                if (after < before) {
                    log.accept("section " + a.getSectionId() + ": kept the first answer (" + before
                            + " words without an error, the repaired one " + after + ")");
                } else {
                    checked = fixed;
                }
            }
        }
        checked = topUp(a, checked, written, book, calls, model);
        for (String marker : checked.result.getStrippedMarkers()) {
            log.accept("section " + a.getSectionId() + ": stripped unknown marker " + marker + " (k = "
                    + a.getPassages().size() + ")");
        }
        logRemoved(a, checked.result);
        Map<Integer, EvidencePassage> citations = new java.util.TreeMap<Integer, EvidencePassage>();
        for (Integer n : checked.result.citedNumbers()) {
            EvidencePassage passage = byNumber(a, n);
            if (passage != null) {
                citations.put(n, passage);
            }
        }
        return new SectionDraft(true, "", checked.result.text(), citations, checked.flags(),
                checked.result.getGaps(), checked.result.getStrippedMarkers(), model[0], hash, calls[0],
                checked.result.wordCount());
    }

    /**
     * Top-ups (live run 5: 256 of 367 words per call): while the checked answer keeps fewer words than the share of
     * the part's target ({@link TopUpCriteria}) and passages of the part are uncited, at most the set number of
     * further calls continue it from what is written. Each joined text is checked again like the first answer; a
     * top-up that adds no words without an error is dropped and ends the top-ups. Sentence tags (SUI-1) stay without
     * top-up, because the written text already carries numbered markers.
     */
    private Checked topUp(SectionAssignment a, Checked checked, java.util.Set<String> written,
                          RepeatCheck.Memory book, int[] calls, String[] model) {
        TopUpCriteria criteria = a.getTopUp();
        if (a.getCitationFormat() == CitationFormat.SENTENCE_TAGS) {
            return checked;
        }
        int target = a.getWordsPerCall();
        int minWords = criteria.minWords(target);
        for (int round = 0; round < criteria.getMaxCalls(); round++) {
            int have = checked.result.wordCount();
            if (have >= minWords) {
                break;
            }
            List<Integer> uncited = new ArrayList<Integer>();
            Set<Integer> cited = checked.result.citedNumbers();
            for (int n = 1; n <= a.getPassages().size(); n++) {
                if (!cited.contains(n)) {
                    uncited.add(n);
                }
            }
            boolean byPoints = criteria.getFocus() == TopUpCriteria.Focus.KEY_POINTS;
            // both foci end when the material is used up: without a new passage a top-up can only rephrase
            if (uncited.isEmpty()) {
                break;
            }
            String text = checked.result.text();
            log.accept("section " + a.getSectionId() + ": top-up " + (round + 1) + " for " + (target - have)
                    + " missing words (" + have + " of " + target + "), " + uncited.size() + " uncited passages"
                    + (byPoints ? ", by key points" : ""));
            MainModelChatResult more = chat.complete(byPoints
                            ? SectionWriterPrompt.topUpByKeyPoints(a, text, target - have)
                            : SectionWriterPrompt.topUp(a, text, uncited, target - have),
                    TEMPERATURE, a.getAnswerTokens());
            calls[0]++;
            if (!more.isOk()) {
                break;
            }
            remember(more, model);
            String rest = more.getText() == null ? "" : more.getText();
            if (more.isCutOff()) {
                rest = upToLastFullSentence(rest);
            }
            rest = withoutFlaggedSentences(a, text, rest, written, book);
            StringBuilder joined = new StringBuilder(join(text, rest));
            for (String gap : checked.result.getGaps()) {
                joined.append("\nGAP: ").append(gap);
            }
            Checked again = check(joined.toString(), a, written, book);
            int before = supportedWords(checked.result, checked.repairable());
            int after = supportedWords(again.result, again.repairable());
            if (after <= before) {
                log.accept("section " + a.getSectionId() + ": top-up dropped (" + after + " words without an error, "
                        + "before " + before + ")");
                break;
            }
            log.accept("section " + a.getSectionId() + ": topped up to " + again.result.wordCount() + " words");
            checked = again;
        }
        return checked;
    }

    /**
     * A top-up gets no repair, so its sentences with an error (uncited, a number or paragraph or quote the cited
     * passage lacks, a citation that does not carry the claim, a source's "we") are left out before it is joined
     * (retest of #246: unsupported numbers 1 -> 6, all paragraph references, and uncarried claims 54 -> 67).
     */
    private String withoutFlaggedSentences(SectionAssignment a, String text, String rest,
                                           java.util.Set<String> written, RepeatCheck.Memory book) {
        java.util.Set<String> before = new java.util.HashSet<String>(written);
        before.addAll(CitationValidator.sentenceKeys(text));
        Checked own = check(rest, a, before, book);
        String kept = rest;
        int dropped = 0;
        for (ClaimFlag error : own.repairable()) {
            String sentence = error.getSentence();
            if (sentence != null && !sentence.trim().isEmpty() && kept.contains(sentence)) {
                kept = kept.replace(sentence, "");
                dropped++;
            }
        }
        if (dropped > 0) {
            log.accept("section " + a.getSectionId() + ": top-up left out " + dropped + " sentences with errors");
        }
        return kept.replaceAll("[ \t]{2,}", " ").trim();
    }

    private String lastFailure = "";

    /** One answer: a call and, when it was cut off, exactly one continuation. Null on a failed call. */
    private String answer(SectionAssignment a, List<ChatMessage> messages, int[] calls, String[] model) {
        MainModelChatResult result = chat.complete(messages, TEMPERATURE, a.getAnswerTokens());
        calls[0]++;
        if (!result.isOk()) {
            lastFailure = result.getDetail();
            return null;
        }
        remember(result, model);
        String text = result.getText() == null ? "" : result.getText();
        if (!result.isCutOff()) {
            return text;
        }
        String cut = upToLastFullSentence(text);
        log.accept("section " + a.getSectionId() + ": answer cut off at " + a.getAnswerTokens()
                + " tokens, one continuation");
        MainModelChatResult more = chat.complete(SectionWriterPrompt.continuation(a, cut), TEMPERATURE,
                a.getAnswerTokens());
        calls[0]++;
        if (!more.isOk()) {
            return cut; // the cut text up to its last full sentence stays usable
        }
        remember(more, model);
        String rest = more.getText() == null ? "" : more.getText();
        if (more.isCutOff()) {
            rest = upToLastFullSentence(rest); // no second continuation: the fragment is dropped
        }
        return join(cut, rest);
    }

    private static void remember(MainModelChatResult result, String[] model) {
        String label = result.getMetrics() == null ? "" : result.getMetrics().getModelLabel();
        if (label != null && !label.isEmpty()) {
            model[0] = label;
        }
    }

    /** The text up to and including its last sentence end (or its last complete line). */
    static String upToLastFullSentence(String text) {
        int end = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                int after = i + 1;
                while (after < text.length() && CLOSERS.indexOf(text.charAt(after)) >= 0) {
                    after++; // a closing quote or bracket belongs to the sentence: "rose.” [1]"
                }
                if (after == text.length() || Character.isWhitespace(text.charAt(after))
                        || text.charAt(after) == '[') {
                    end = after;
                    break;
                }
            }
        }
        if (end < 0) {
            return "";
        }
        // keep markers written right after the full stop: "... rose. [2]"
        java.util.regex.Matcher tail = java.util.regex.Pattern.compile("^(\\s*\\[[\\d,; ]+\\])+")
                .matcher(text.substring(end));
        if (tail.find()) {
            end += tail.end();
        }
        return text.substring(0, end).trim();
    }

    /** Joins a continuation to the cut text, dropping sentences the continuation repeats. */
    static String join(String cut, String continuation) {
        String more = continuation.trim();
        List<CitationValidator.Sentence> written = CitationValidator.sentences(cut.replace('\n', ' '));
        List<CitationValidator.Sentence> next = CitationValidator.sentences(more.split("\n", 2)[0]);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (CitationValidator.Sentence s : written) {
            seen.add(ClaimSupportCheck.normaliseText(s.plainText()));
        }
        int skip = 0;
        for (CitationValidator.Sentence s : next) {
            if (!seen.contains(ClaimSupportCheck.normaliseText(s.plainText()))) {
                break;
            }
            skip += 1;
        }
        if (skip > 0) {
            String firstLine = more.split("\n", 2)[0];
            StringBuilder kept = new StringBuilder();
            for (int i = skip; i < next.size(); i++) {
                kept.append(kept.length() == 0 ? "" : " ").append(next.get(i).getText());
            }
            more = kept + more.substring(firstLine.length());
            more = more.trim();
        }
        if (more.isEmpty()) {
            return cut;
        }
        boolean newParagraph = continuation.startsWith("\n");
        if (more.regionMatches(true, 0, "GAP", 0, 3) && more.substring(3).trim().startsWith(":")) {
            return cut + "\n" + more; // a gap line must start its own line to be parsed
        }
        return cut + (newParagraph ? "\n\n" : " ") + more;
    }

    private Checked check(String rawAnswer, SectionAssignment a, java.util.Set<String> written,
                          RepeatCheck.Memory book) {
        // a writer citing sentence tags (SUI-1) is read as [n] markers, so every check below stays one path
        String answer = a.getCitationFormat() == CitationFormat.SENTENCE_TAGS
                ? SentenceTags.of(a.getPassages()).toNumbered(rawAnswer) : rawAnswer;
        final int target = a.getWordsPerCall();
        // the target echoed back is no open point; a number the material names too may be one ("450 Beschäftigte")
        final boolean onlyTheTargetNamesIt = !namesNumber(SectionWriterPrompt.withoutWordTarget(a), target);
        CitationValidator.Result result = validator.validate(answer, a.getPassages().size(), written, book)
                .withoutGaps(new java.util.function.Predicate<String>() {
                    public boolean test(String point) {
                        return onlyTheTargetNamesIt && namesNumber(point, target);
                    }
                });
        List<ClaimFlag> unsupported = new ArrayList<ClaimFlag>(claims.check(result, a.getPassages()));
        unsupported.addAll(new ClaimCarryCheck(a.getCarry(), similarity).check(result, a.getPassages()));
        unsupported.addAll(voice.check(result));
        return new Checked(result, unsupported);
    }

    /** One count line for what the check took out of the prose (never the text itself). */
    private void logRemoved(SectionAssignment a, CitationValidator.Result result) {
        int labels = result.getLabelLines().size();
        int repeats = result.getRepeatedSentences().size();
        int questions = result.getQuestions().size();
        int gaps = result.getDroppedGaps().size();
        if (labels + repeats + questions + gaps > 0) {
            log.accept("section " + a.getSectionId() + ": removed " + labels + " label lines and " + repeats
                    + " repeated sentences, moved " + questions + " uncited questions to the open points, dropped "
                    + gaps + " gap lines that are no open point");
        }
    }

    /**
     * Whether a text names {@code number} as a number of its own ("1100", "1.100", "1 100"). An open point that names
     * the call's word target, while nothing else the writer read names that number, talks about the writing ("Zielwert
     * von 1100 Wörtern"), not about what the passages lack; structural, like the other checks, with no word list.
     */
    static boolean namesNumber(String text, int number) {
        java.util.regex.Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            String digits = m.group().replaceAll("[^0-9]", "");
            if (digits.length() <= 9 && Integer.parseInt(digits) == number) {
                return true;
            }
        }
        return false;
    }

    /** A number with optional thousands separators (dot, comma, apostrophe, plain or narrow no-break space). */
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\d{1,3}(?:[.,'\u00a0\u202f ]\\d{3})+(?![\\p{L}\\p{N}])"
                    + "|(?<![\\p{L}\\p{N}.,])\\d+(?![\\p{L}\\p{N}])");

    private static EvidencePassage byNumber(SectionAssignment a, int n) {
        for (EvidencePassage p : a.getPassages()) {
            if (p.getNumber() == n) {
                return p;
            }
        }
        return null;
    }

    /**
     * Splits a section into key-point parts when it needs more words than one call writes; each part carries the
     * passages of the pack it gets ({@link #passagesFor}), with the pack's numbers, and in a rewrite its share of the
     * current text ({@link #currentTextShares}).
     */
    static List<SectionAssignment> parts(SectionAssignment a) {
        List<SectionAssignment> parts = new ArrayList<SectionAssignment>();
        int per = a.getWordsPerCall();
        List<String> points = a.getKeyPoints();
        int count = SectionAssignment.partCount(a.getSectionWords(), per, points.size());
        int words = count == 1 ? Math.min(a.getSectionWords(), per)
                : Math.min(per, (a.getSectionWords() + count - 1) / count);
        // a part rewrites at most its own word target of the old text (SectionAssignment raises it to the minimum)
        List<String> shares = currentTextShares(a.getCurrentText(), count,
                Math.max(SectionAssignment.MIN_WORDS_PER_CALL, words));
        if (a.isBound()) {
            for (int i = 0; i < count; i++) {
                int from = i * points.size() / count;
                int to = (i + 1) * points.size() / count;
                parts.add(a.part(new ArrayList<String>(points.subList(from, to)), boundPassages(a, from, to), words,
                        a.getPreviousDigest(), shares.get(i)));
            }
            return parts;
        }
        if (count == 1) {
            parts.add(a.part(points, a.getPassages(), words, a.getPreviousDigest(), shares.get(0)));
            return parts;
        }
        ClaimCarryCheck measure = new ClaimCarryCheck(a.getCarry());
        Set<Integer> taken = new HashSet<Integer>();
        for (int i = 0; i < count; i++) {
            int from = i * points.size() / count;
            int to = (i + 1) * points.size() / count;
            List<String> partPoints = new ArrayList<String>(points.subList(from, to));
            parts.add(a.part(partPoints, passagesFor(a, partPoints, taken, measure), words, a.getPreviousDigest(),
                    shares.get(i)));
        }
        return parts;
    }

    /**
     * The share of a rewrite's current text that each of {@code count} parts rewrites (plan, "Rewrite section part":
     * the current text of the part, up to the per-call word target). The sentences keep their order; each goes to the
     * part in whose k-th of the text its middle lies, paragraph breaks stay, and a share takes no sentence beyond
     * {@code max} words. Live run 4: every part got the whole old section, copied its sentences (uncited, the old
     * markers are left out of the prompt) and repeated the other parts. Empty shares for a first draft.
     */
    static List<String> currentTextShares(String text, int count, int max) {
        List<String> sentences = new ArrayList<String>();
        List<Integer> paragraphs = new ArrayList<Integer>();
        List<Integer> sizes = new ArrayList<Integer>();
        int total = 0;
        String[] blocks = text == null || text.trim().isEmpty() ? new String[0] : text.trim().split("\\n\\s*\\n");
        for (int p = 0; p < blocks.length; p++) {
            for (CitationValidator.Sentence sentence : CitationValidator.sentences(blocks[p].replace('\n', ' '))) {
                String plain = sentence.plainText();
                int size = plain.isEmpty() ? 0 : plain.split("\\s+").length;
                sentences.add(sentence.getText());
                paragraphs.add(p);
                sizes.add(size);
                total += size;
            }
        }
        StringBuilder[] shares = new StringBuilder[count];
        int[] used = new int[count];
        int[] lastParagraph = new int[count];
        for (int i = 0; i < count; i++) {
            shares[i] = new StringBuilder();
            lastParagraph[i] = -1;
        }
        int before = 0;
        for (int j = 0; j < sentences.size(); j++) {
            int size = sizes.get(j);
            int part = (int) Math.min(count - 1, (2L * before + size) * count / (2L * Math.max(1, total)));
            before += size;
            if (used[part] + size > max) {
                continue;
            }
            if (shares[part].length() > 0) {
                shares[part].append(lastParagraph[part] == paragraphs.get(j) ? " " : "\n\n");
            }
            shares[part].append(sentences.get(j));
            used[part] += size;
            lastParagraph[part] = paragraphs.get(j);
        }
        List<String> out = new ArrayList<String>();
        for (StringBuilder share : shares) {
            out.add(share.toString());
        }
        return out;
    }

    /**
     * The passages of one part: every passage of the pack when it holds no more than one call gets, otherwise the
     * {@link SectionAssignment#getPassagesPerCall() passages per call} that cover the most content words of the
     * part's key points (the measure of claim-check stage 2), passages no earlier part took first, ties in pack
     * order (the pack is ranked by the section brief). In pack order, with the pack's numbers.
     *
     * @param taken the numbers earlier parts took; this part's numbers are added
     */
    static List<EvidencePassage> passagesFor(SectionAssignment a, List<String> partPoints, Set<Integer> taken,
                                             ClaimCarryCheck measure) {
        List<EvidencePassage> pack = a.getPassages();
        int count = a.getPassagesPerCall();
        if (count <= 0 || pack.size() <= count) {
            return pack;
        }
        StringBuilder points = new StringBuilder();
        for (String point : partPoints) {
            points.append(point).append(' ');
        }
        List<String> words = measure.contentWords(points.toString());
        final Map<Integer, Double> cover = new java.util.HashMap<Integer, Double>();
        for (EvidencePassage passage : pack) {
            cover.put(passage.getNumber(), measure.overlap(words, measure.stemsOf(passage.getText())));
        }
        final Set<Integer> earlier = new HashSet<Integer>(taken);
        List<EvidencePassage> ranked = new ArrayList<EvidencePassage>(pack);
        java.util.Collections.sort(ranked, new java.util.Comparator<EvidencePassage>() {
            public int compare(EvidencePassage x, EvidencePassage y) {
                boolean xTaken = earlier.contains(x.getNumber());
                if (xTaken != earlier.contains(y.getNumber())) {
                    return xTaken ? 1 : -1;
                }
                int byCover = Double.compare(cover.get(y.getNumber()), cover.get(x.getNumber()));
                return byCover != 0 ? byCover : Integer.compare(x.getNumber(), y.getNumber());
            }
        });
        List<EvidencePassage> chosen = new ArrayList<EvidencePassage>(ranked.subList(0, count));
        java.util.Collections.sort(chosen, new java.util.Comparator<EvidencePassage>() {
            public int compare(EvidencePassage x, EvidencePassage y) {
                return Integer.compare(x.getNumber(), y.getNumber());
            }
        });
        for (EvidencePassage passage : chosen) {
            taken.add(passage.getNumber());
        }
        return chosen;
    }

    /**
     * The passages bound to the key points {@code from} to {@code to} (exclusive) of a bound assignment (component
     * 10): one passage of each key point in turn, each key point's in the order the binding gives (direct evidence
     * first), a passage bound to two key points once, up to {@link SectionAssignment#getPassagesPerCall()} (0 = all).
     * Returned in pack order.
     */
    static List<EvidencePassage> boundPassages(SectionAssignment a, int from, int to) {
        List<List<Integer>> binding = a.getKeyPointPassages();
        int cap = a.getPassagesPerCall();
        Set<Integer> chosen = new java.util.TreeSet<Integer>();
        boolean more = true;
        for (int round = 0; more && (cap <= 0 || chosen.size() < cap); round++) {
            more = false;
            for (int k = from; k < to && (cap <= 0 || chosen.size() < cap); k++) {
                List<Integer> numbers = binding.get(k);
                if (round < numbers.size()) {
                    more = true;
                    chosen.add(numbers.get(round));
                }
            }
        }
        List<EvidencePassage> out = new ArrayList<EvidencePassage>();
        for (Integer n : chosen) {
            EvidencePassage passage = byNumber(a, n);
            if (passage != null) {
                out.add(passage);
            }
        }
        return out;
    }

    /**
     * Whether the passages are numbered {@code [1]..[m]} in their order; a bound part's choice keeps the pack's
     * numbers, so it is written like a part of several and its markers go back to them.
     */
    static boolean consecutive(List<EvidencePassage> passages) {
        for (int i = 0; i < passages.size(); i++) {
            if (passages.get(i).getNumber() != i + 1) {
                return false;
            }
        }
        return true;
    }

    /** The passages numbered {@code [1]..[m]} in their order, as one call's prompt lists them. */
    static List<EvidencePassage> numbered(List<EvidencePassage> passages) {
        List<EvidencePassage> out = new ArrayList<EvidencePassage>();
        for (EvidencePassage p : passages) {
            out.add(p.withNumber(out.size() + 1));
        }
        return out;
    }

    /** A part's markers {@code [i]} as the numbers of {@code packed.get(i - 1)}; other numbers stay. */
    static String renumber(String text, List<EvidencePassage> packed) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        java.util.regex.Matcher m = CitationValidator.MARKER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            List<String> numbers = new ArrayList<String>();
            for (String n : m.group(1).split("[,;]")) {
                int local = Integer.parseInt(n.trim());
                numbers.add(String.valueOf(local >= 1 && local <= packed.size()
                        ? packed.get(local - 1).getNumber() : local));
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("[" + String.join(", ", numbers)
                    + "]"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The words of an answer outside the sentences an error names; each sentence counts once. */
    static int supportedWords(CitationValidator.Result result, List<ClaimFlag> errors) {
        int words = result.wordCount();
        Set<String> counted = new HashSet<String>();
        for (ClaimFlag error : errors) {
            if (counted.add(CitationValidator.pointKey(error.getSentence()))) {
                String plain = CitationValidator.MARKER.matcher(error.getSentence()).replaceAll(" ").trim();
                words -= plain.isEmpty() ? 0 : plain.split("\\s+").length;
            }
        }
        return Math.max(0, words);
    }

    private static String lastSentences(String text) {
        List<CitationValidator.Sentence> s = CitationValidator.sentences(text.replace('\n', ' '));
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, s.size() - 3); i < s.size(); i++) {
            sb.append(sb.length() == 0 ? "" : " ").append(s.get(i).plainText());
        }
        return sb.toString();
    }

    private static final class Checked {
        final CitationValidator.Result result;
        final List<ClaimFlag> unsupported;

        Checked(CitationValidator.Result result, List<ClaimFlag> unsupported) {
            this.result = result;
            this.unsupported = unsupported;
        }

        List<ClaimFlag> flags() {
            List<ClaimFlag> all = new ArrayList<ClaimFlag>(result.getFlags());
            all.addAll(unsupported);
            return all;
        }

        /** What the repair lists: uncited sentences, unsupported numbers and quotes, and a source's voice. */
        List<ClaimFlag> repairable() {
            List<ClaimFlag> out = new ArrayList<ClaimFlag>();
            for (ClaimFlag f : flags()) {
                if (f.getKind() != ClaimFlag.Kind.UNCITED_PARAGRAPH) {
                    out.add(f);
                }
            }
            return out;
        }
    }
}
