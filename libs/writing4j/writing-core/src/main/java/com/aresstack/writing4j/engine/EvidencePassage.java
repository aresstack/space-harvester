package com.aresstack.writing4j.engine;

/**
 * One numbered passage of a section's evidence pack (plan D8, PR 27): the local number {@code [n]} the writer
 * cites, the source it comes from with that source's checksum, the passage id as a derived link, and the text
 * exactly as it was cut for the pack. A note, when set, stands next to the passage in the writer's prompt (component
 * 10: direct evidence or only a lead, a source with an interest of its own, another legal space, an open
 * contradiction). Immutable.
 */
public final class EvidencePassage {

    private final int number;
    private final String sourceId;
    private final String sourceChecksum;
    private final String passageId;
    private final String text;
    private final String note;

    public EvidencePassage(int number, String sourceId, String sourceChecksum, String passageId, String text) {
        this(number, sourceId, sourceChecksum, passageId, text, "");
    }

    /** @param note what the writer should know about the passage, shown next to it; {@code ""} for none */
    public EvidencePassage(int number, String sourceId, String sourceChecksum, String passageId, String text,
                           String note) {
        if (number < 1) {
            throw new IllegalArgumentException("number must be 1 or more");
        }
        this.number = number;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.sourceChecksum = sourceChecksum == null ? "" : sourceChecksum;
        this.passageId = passageId == null ? "" : passageId;
        this.text = text == null ? "" : text;
        this.note = note == null ? "" : note.replaceAll("\\s+", " ").trim();
    }

    public int getNumber() {
        return number;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourceChecksum() {
        return sourceChecksum;
    }

    public String getPassageId() {
        return passageId;
    }

    public String getText() {
        return text;
    }

    /** What the writer should know about the passage; {@code ""} for none. */
    public String getNote() {
        return note;
    }

    /** The same passage under another number, its note kept. */
    public EvidencePassage withNumber(int newNumber) {
        return newNumber == number ? this : new EvidencePassage(newNumber, sourceId, sourceChecksum, passageId, text,
                note);
    }
}
