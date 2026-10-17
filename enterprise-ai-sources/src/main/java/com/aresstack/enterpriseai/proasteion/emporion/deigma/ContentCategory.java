package com.aresstack.enterpriseai.proasteion.emporion.deigma;

/**
 * Broad classification of content for routing decisions.
 *
 * <p>Source-code files are detected separately from plain text
 * ({@link #SOURCE_CODE}); both are indexed as text.
 */
public enum ContentCategory {

    /** Plain text content. */
    PLAIN_TEXT,

    /** Markdown formatted text. */
    MARKDOWN,

    /** HTML content. */
    HTML,

    /** PDF document. */
    PDF,

    /** Office document (DOCX, XLSX, PPTX, etc.). */
    OFFICE_DOCUMENT,

    /** Source code (indexed as plain text). */
    SOURCE_CODE,

    /** Structured data (CSV, JSON, XML). */
    STRUCTURED_DATA,

    /** Binary or unknown content. */
    BINARY,

    /** Content type could not be determined. */
    UNKNOWN
}
