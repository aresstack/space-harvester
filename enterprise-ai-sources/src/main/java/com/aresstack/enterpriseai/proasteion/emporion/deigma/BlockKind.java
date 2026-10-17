package com.aresstack.enterpriseai.proasteion.emporion.deigma;

/**
 * Classification of an extracted block's structural role.
 */
public enum BlockKind {

    /** General text paragraph. */
    TEXT,

    /** Heading or title. */
    HEADING,

    /** Table or tabular data. */
    TABLE,

    /** Code or preformatted block. */
    CODE,

    /** Metadata entry (key-value). */
    METADATA,

    /** List item or list block. */
    LIST,

    /** Block type not otherwise classified. */
    OTHER
}
