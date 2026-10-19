package com.aresstack.spaceharvester.document;

/**
 * One figure of an exported page (#132): where the page shows it ({@link #src()}, the address the export Markdown
 * refers to), its caption or alternative text, and either the bytes the source delivered through its own sign-in or the
 * reason they could not be loaded. Binary data lives here, never in the text of a chapter. Immutable.
 */
public final class ExportImage {

    private final String src;
    private final String title;
    private final byte[] bytes;
    private final String failure;

    private ExportImage(String src, String title, byte[] bytes, String failure) {
        if (src == null || src.trim().isEmpty()) {
            throw new IllegalArgumentException("src is required");
        }
        this.src = src;
        this.title = title == null ? "" : title.trim();
        this.bytes = bytes;
        this.failure = failure;
    }

    /**
     * A figure whose bytes were loaded. The figure takes the array over without copying it (an attachment may have
     * 100 MiB); the caller must not change it afterwards.
     */
    public static ExportImage loaded(String src, String title, byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes are required");
        }
        return new ExportImage(src, title, bytes, null);
    }

    /** A figure that could not be loaded, with the reason the export names. */
    public static ExportImage failed(String src, String title, String reason) {
        return new ExportImage(src, title, null,
                reason == null || reason.trim().isEmpty() ? "nicht ladbar" : reason.trim());
    }

    public String src() {
        return src;
    }

    /** Caption or alternative text; empty if the page names none. */
    public String title() {
        return title;
    }

    /** Whether the bytes are there. */
    public boolean ok() {
        return bytes != null;
    }

    /** The bytes as the source delivered them; {@code null} if not loaded. */
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    /** The bytes without a copy, for reading inside this package only; {@code null} if not loaded. */
    byte[] shared() {
        return bytes;
    }

    /** Why the figure is missing; {@code null} if loaded. */
    public String failure() {
        return failure;
    }
}
