package com.aresstack.spaceharvester.mainframe.dependency;

import java.util.List;

/**
 * Externe Aufrufe eines COBOL-Programms (in MainframeMate {@code CodeAnalyticsService.extractExternalCalls} mit
 * {@code SourceLanguage.COBOL}): je Aufruf Art (CALL, EXEC CICS LINK …) und Ziel.
 */
@FunctionalInterface
public interface CobolCalls {

    record Call(String type, String target, int line) {
    }

    List<Call> extract(String content, String name);
}
