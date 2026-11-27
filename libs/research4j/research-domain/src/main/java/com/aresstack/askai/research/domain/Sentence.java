package com.aresstack.askai.research.domain;

/** One segmented sentence of a capture's structural block — the atomic unit of the knowledge pipeline. */
public final class Sentence {

    private final String sentenceId;
    private final String captureId;
    private final String blockId;
    private final int ordinal;
    private final String text;

    public Sentence(String sentenceId, String captureId, String blockId, int ordinal, String text) {
        this.sentenceId = sentenceId == null ? "" : sentenceId;
        this.captureId = captureId == null ? "" : captureId;
        this.blockId = blockId == null ? "" : blockId;
        this.ordinal = ordinal;
        this.text = text == null ? "" : text;
    }

    public String getSentenceId() {
        return sentenceId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getBlockId() {
        return blockId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getText() {
        return text;
    }
}
