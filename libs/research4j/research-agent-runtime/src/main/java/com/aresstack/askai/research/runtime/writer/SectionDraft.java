package com.aresstack.askai.research.runtime.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of writing one section (plan PR 28): the checked text with its local {@code [n]} markers, the passage
 * of every cited number (source id, checksum, passage text as the writer saw it), the claim-check flags, the open
 * points of the {@code GAP:} lines, the markers that were stripped, the model label and the hash of the first
 * prompt. A failed draft carries only the reason. Immutable.
 */
public final class SectionDraft {

    private final boolean ok;
    private final String failure;
    private final String text;
    private final Map<Integer, EvidencePassage> citations;
    private final List<ClaimFlag> flags;
    private final List<String> gaps;
    private final List<String> strippedMarkers;
    private final String modelLabel;
    private final String promptHash;
    private final int calls;
    private final int wordCount;

    SectionDraft(boolean ok, String failure, String text, Map<Integer, EvidencePassage> citations,
                 List<ClaimFlag> flags, List<String> gaps, List<String> strippedMarkers, String modelLabel,
                 String promptHash, int calls, int wordCount) {
        this.ok = ok;
        this.failure = failure == null ? "" : failure;
        this.text = text == null ? "" : text;
        this.citations = Collections.unmodifiableMap(new LinkedHashMap<Integer, EvidencePassage>(citations));
        this.flags = Collections.unmodifiableList(new ArrayList<ClaimFlag>(flags));
        this.gaps = Collections.unmodifiableList(new ArrayList<String>(gaps));
        this.strippedMarkers = Collections.unmodifiableList(new ArrayList<String>(strippedMarkers));
        this.modelLabel = modelLabel == null ? "" : modelLabel;
        this.promptHash = promptHash == null ? "" : promptHash;
        this.calls = calls;
        this.wordCount = wordCount;
    }

    static SectionDraft failed(String reason, String promptHash, int calls) {
        return new SectionDraft(false, reason, "", Collections.<Integer, EvidencePassage>emptyMap(),
                Collections.<ClaimFlag>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), "", promptHash, calls, 0);
    }

    public boolean isOk() {
        return ok;
    }

    public String getFailure() {
        return failure;
    }

    public String getText() {
        return text;
    }

    /** Local number to the cited passage, for the citation map of the section revision (PR 26). */
    public Map<Integer, EvidencePassage> getCitations() {
        return citations;
    }

    public List<ClaimFlag> getFlags() {
        return flags;
    }

    public List<ClaimFlag> flagsOf(ClaimFlag.Kind kind) {
        List<ClaimFlag> out = new ArrayList<ClaimFlag>();
        for (ClaimFlag flag : flags) {
            if (flag.getKind() == kind) {
                out.add(flag);
            }
        }
        return out;
    }

    public List<String> getGaps() {
        return gaps;
    }

    public List<String> getStrippedMarkers() {
        return strippedMarkers;
    }

    public String getModelLabel() {
        return modelLabel;
    }

    public String getPromptHash() {
        return promptHash;
    }

    /** Model calls this draft took (first call, continuations, repair). */
    public int getCalls() {
        return calls;
    }

    public int getWordCount() {
        return wordCount;
    }
}
