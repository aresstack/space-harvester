package com.aresstack.enterpriseai.astu.acropolis.textmining;

/** What a capitalized or mixed token looks like, read from its spelling only. */
public enum TermKind {
    /** Letters mostly in capitals, at most a few digits: {@code IAR}, {@code GmbH}, {@code B2B}, {@code U.S.A.}. */
    ACRONYM,
    /** Letters and digits mixed or joined by underscores: {@code ABC-1234}, {@code MAX_SIZE}, {@code X12Y7}. */
    TECHNICAL_IDENTIFIER
}
