package com.aresstack.knowledge4j.source;

import java.util.Optional;

/**
 * Where exactly inside a source a statement was found. Every part is optional; a host fills what its source
 * knows: lines of a program, the page of a document, a section, a character range, an attachment.
 *
 * @param firstLine  first line, 1-based, or 0 when unknown
 * @param lastLine   last line, 1-based and not before {@code firstLine}, or 0 when unknown
 * @param page       page, 1-based, or 0 when unknown
 * @param section    section heading or path, empty when unknown
 * @param charStart  start of the character range, or -1 when unknown
 * @param charEnd    end of the character range (exclusive), or -1 when unknown
 * @param attachment name of the attachment, empty when the statement is in the source itself
 */
public record SourceLocation(int firstLine, int lastLine, int page, String section, int charStart, int charEnd,
                             String attachment) {

    public static final SourceLocation WHOLE = new SourceLocation(0, 0, 0, "", -1, -1, "");

    public SourceLocation {
        section = section == null ? "" : section;
        attachment = attachment == null ? "" : attachment;
        if (firstLine < 0 || lastLine < 0 || page < 0) {
            throw new IllegalArgumentException("lines and page must not be negative");
        }
        if (lastLine != 0 && lastLine < firstLine) {
            throw new IllegalArgumentException("last line before first line");
        }
        if ((charStart < 0) != (charEnd < 0) || (charStart >= 0 && charEnd < charStart)) {
            throw new IllegalArgumentException("invalid character range " + charStart + ".." + charEnd);
        }
    }

    public static SourceLocation lines(int first, int last) {
        return new SourceLocation(first, last, 0, "", -1, -1, "");
    }

    public static SourceLocation page(int page) {
        return new SourceLocation(0, 0, page, "", -1, -1, "");
    }

    public static SourceLocation chars(int start, int end) {
        return new SourceLocation(0, 0, 0, "", start, end, "");
    }

    public SourceLocation withSection(String newSection) {
        return new SourceLocation(firstLine, lastLine, page, newSection, charStart, charEnd, attachment);
    }

    public SourceLocation withAttachment(String newAttachment) {
        return new SourceLocation(firstLine, lastLine, page, section, charStart, charEnd, newAttachment);
    }

    public Optional<String> sectionIfKnown() {
        return section.isEmpty() ? Optional.empty() : Optional.of(section);
    }
}
