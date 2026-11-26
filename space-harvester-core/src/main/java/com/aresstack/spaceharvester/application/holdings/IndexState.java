package com.aresstack.spaceharvester.application.holdings;

/** Describe the full-text availability of a local file independently of its cached metadata. */
public enum IndexState {
    FOLDER, INDEXED, PARTIAL, OUTDATED, NOT_INDEXED, NO_TEXT, FAILED, LOCKED
}
