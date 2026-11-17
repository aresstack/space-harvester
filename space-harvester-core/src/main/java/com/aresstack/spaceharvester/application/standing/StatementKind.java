package com.aresstack.spaceharvester.application.standing;

/** Art einer Aussage in der Status-quo-Sicht (ADR 0032). */
public enum StatementKind {
    /** Ein Verb mit Handelndem und Betroffenen. */
    ACTION,
    /** Ein Zustand mit Träger ({@code BEARER}): Eigenschaft oder Rollennomen. */
    STATE
}
