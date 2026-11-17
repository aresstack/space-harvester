package com.aresstack.spaceharvester.application.standing;

/**
 * Grund einer Teilzahl der Leseempfehlung (ADR 0032, FL6). Jede Auf- und Abwertung ist eine offene Teilzahl mit
 * Grund; die Punkte je Grund stehen in {@link ReadingAdvice}, keine verdeckte Formel.
 */
public enum ReadingReason {
    /** Je sichere Aussage, die gilt: +{@value ReadingAdvice#CURRENT_STATEMENT}. */
    CURRENT_STATEMENTS,
    /** Je sichere Aussage, die überholt ist: {@value ReadingAdvice#OUTDATED_STATEMENT}. */
    OUTDATED_STATEMENTS,
    /** Je häufigster Begriff der Auswahl auf der Seite: +{@value ReadingAdvice#TOP_TERM}. */
    TOP_TERMS,
    /** Je Erwähnung des gewählten Themas: +{@value ReadingAdvice#TOPIC_MENTION}. */
    TOPIC_MENTIONS,
    /** Inhalt im Zeitraum neu oder geändert (Änderungsprotokoll): +{@value ReadingAdvice#RECENT_CHANGE}. */
    RECENT_CHANGE,
    /** Seite überholt (Label, Titel, Lage oder Entscheidung): {@value ReadingAdvice#PAGE_OUTDATED}. */
    PAGE_OUTDATED,
    /** Seite Entwurf oder vom Menschen als geplant/unklar eingestuft: {@value ReadingAdvice#PAGE_DRAFT}. */
    PAGE_DRAFT,
    /** Analyse der Seite veraltet: {@value ReadingAdvice#STALE_ANALYSIS}. */
    STALE_ANALYSIS
}
