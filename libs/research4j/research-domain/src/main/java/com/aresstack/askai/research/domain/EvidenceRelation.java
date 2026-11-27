package com.aresstack.askai.research.domain;

/** How a passage relates to a claim — contradictions stay VISIBLE, they are never averaged away. */
public enum EvidenceRelation {
    SUPPORTS,
    CONTRADICTS,
    QUALIFIES,
    PROVIDES_CONTEXT
}
