package com.aresstack.spaceharvester.application.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ergebnis einer Satzanalyse im Core-Modell (#499), unabhängig vom Parser. Rohstruktur ({@link #conllu}) und fachliche
 * Ableitungen (Erwähnungen, Aussagen, nominale Bezüge, Kernsicht) bleiben getrennt; die Ableitungen verweisen über
 * IDs aufeinander, nie auf Entitäten.
 *
 * @param sentences   Sätze mit Spanne im Text, in Textreihenfolge
 * @param mentions    Erwähnungen in Textreihenfolge
 * @param statements  Aussagen in Textreihenfolge
 * @param attachments nominale Bezüge
 * @param components  Werkzeuge und Modelle je Schicht, z. B. {@code parser=maltparser-1.9.2 model=…@sha256:…}
 * @param conllu      vollständige UD-Analyse als CoNLL-U (Syntaxdetails, Diagnose) oder {@code null}, wenn sie nicht
 *                    geladen wurde
 */
public record ParsedStatements(List<Sentence> sentences, List<Mention> mentions, List<Statement> statements,
                               List<Attachment> attachments, List<String> components, String conllu) {

    public ParsedStatements {
        sentences = List.copyOf(sentences);
        mentions = List.copyOf(mentions);
        statements = List.copyOf(statements);
        attachments = List.copyOf(attachments);
        components = List.copyOf(components);
    }

    /** Dieselbe Analyse ohne CoNLL-U, etwa für den Speicher des Graphen. */
    public ParsedStatements withoutConllu() {
        return conllu == null ? this : new ParsedStatements(sentences, mentions, statements, attachments, components,
                null);
    }

    public ParsedStatements withConllu(String raw) {
        return new ParsedStatements(sentences, mentions, statements, attachments, components, raw);
    }

    /**
     * Ein Satz.
     *
     * @param index Satzindex ab 0
     * @param start Zeichenposition im Text (inklusive)
     * @param end   Zeichenposition im Text (exklusive)
     */
    public record Sentence(int index, int start, int end) {
    }

    /**
     * Eine Erwähnung: eine Nominalgruppe oder ein Pronomen an genau einer Textstelle.
     *
     * @param id         {@code quelle/seite@fassung#s<Satz>.t<Kopf>}
     * @param sentence   Satzindex
     * @param start      Zeichenposition im Text (inklusive)
     * @param end        Zeichenposition im Text (exklusive)
     * @param text       Wortlaut der Gruppe
     * @param headForm   Form des Kopfs
     * @param lemma      Grundform des Kopfs oder {@code null}
     * @param kind       Art nach der Wortart des Kopfs
     * @param unresolved Pronomen mit offenem Bezug; wird nie geraten
     * @param cases      mögliche Kasus ({@code Nom}, {@code Acc}, {@code Dat}, {@code Gen}); leer = unbekannt
     */
    public record Mention(String id, int sentence, int start, int end, String text, String headForm, String lemma,
                          MentionKind kind, boolean unresolved, Set<String> cases) {

        public Mention {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(kind, "kind");
            cases = Set.copyOf(cases);
        }

        /** Schlüssel für lexikalische Vergleiche: Grundform, sonst Kopfform. Nie eine Identität. */
        public String key() {
            return lemma != null && !lemma.isBlank() ? lemma : headForm;
        }
    }

    /** Art einer Erwähnung. */
    public enum MentionKind {
        NOUN,
        PROPER_NOUN,
        PRONOUN,
        OTHER
    }

    /**
     * Eine Aussage: ein Prädikat in einem Satz mit seinen Beteiligten.
     *
     * @param id             {@code quelle/seite@fassung#s<Satz>.v<Verb>}
     * @param sentence       Satzindex
     * @param start          Beginn des Prädikats im Text (erstes Prädikatsteil)
     * @param end            Ende des Prädikats im Text (letztes Prädikatsteil)
     * @param predicateText  Prädikat im Wortlaut ({@code ruft … an})
     * @param predicateLemma Grundform ({@code anrufen}) oder {@code null}
     * @param voice          Aktiv oder Passiv
     * @param clause         Haupt-, koordinierter oder Nebensatz
     * @param core           Kernaussage der priorisierten Sicht (aktiv, kein Nebensatz, Subjekt und Objekt aus
     *                       eigenen, widerspruchsfreien Parserkanten)
     * @param arguments      Beteiligte, Kern zuerst
     * @param findings       erkannte, aber nicht aufgelöste Besonderheiten ({@code PASSIVE}, {@code UNRESOLVED_PRONOUN},
     *                       {@code TIME_FROM_NOMINAL} …)
     */
    public record Statement(String id, int sentence, int start, int end, String predicateText, String predicateLemma,
                            Voice voice, Clause clause, boolean core, List<Argument> arguments, Set<String> findings) {

        public Statement {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(predicateText, "predicateText");
            Objects.requireNonNull(voice, "voice");
            Objects.requireNonNull(clause, "clause");
            arguments = List.copyOf(arguments);
            findings = Set.copyOf(findings);
        }

        /** Alle Beteiligten mit dieser Rolle. */
        public List<Argument> withRole(Role role) {
            return arguments.stream().filter(argument -> argument.role() == role).toList();
        }

        /** Ob alle Beteiligten aus sicheren Parserkanten oder der Wortliste stammen. */
        public boolean certain() {
            return arguments.stream().allMatch(argument -> argument.certain()
                    && (argument.basis() == Basis.PARSER || argument.basis() == Basis.LEXICON));
        }
    }

    public enum Voice {
        ACTIVE,
        PASSIVE
    }

    public enum Clause {
        MAIN,
        COORDINATED,
        SUBORDINATE
    }

    /**
     * Ein Beteiligter einer Aussage.
     *
     * @param role     semantische Rolle
     * @param mention  ID der Erwähnung
     * @param relation UD-Relation ({@code nsubj}, {@code obj}, {@code obl:arg}, {@code nmod}) oder Vorschlag des
     *                 Rückfalls ({@code obj?})
     * @param marker   Präposition im Wortlaut oder {@code null}
     * @param priority Rang
     * @param basis    Herkunft der Zuordnung
     * @param certain  {@code false} bei abgeleiteter, widersprüchlicher oder unbestätigter Zuordnung
     */
    public record Argument(Role role, String mention, String relation, String marker, Priority priority, Basis basis,
                           boolean certain) {

        public Argument {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(mention, "mention");
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(basis, "basis");
        }
    }

    /** Semantische Rolle wie im Aussagenmodell des Enterprise AI Client und in ADR 0005. */
    public enum Role {
        ACTOR,
        AFFECTED_OBJECT,
        RECIPIENT,
        PREPOSITIONAL_OBJECT,
        INSTRUMENT,
        TIME,
        CIRCUMSTANCE
    }

    public enum Priority {
        CORE,
        SUPPLEMENTARY,
        CONTEXT
    }

    /** Herkunft einer Zuordnung; alles außer {@link #PARSER} ist eine Ableitung. */
    public enum Basis {
        PARSER,
        COORDINATION,
        CASE_HEURISTIC,
        LEXICON,
        /** Strukturelle Heuristik gegen eine erkennbar fehlerhafte Parserkante; immer unsicher. */
        HEURISTIC
    }

    /**
     * Ein nominaler Bezug ({@code der Beleg des Kunden}): der Text verbindet zwei Gruppen. Kein Besitz, keine Herkunft.
     *
     * @param id        eindeutige ID
     * @param head      Erwähnung, an der die Ergänzung hängt
     * @param dependent Erwähnung der Ergänzung
     * @param relation  UD-Relation ({@code nmod}, {@code nmod:poss})
     * @param marker    Präposition im Wortlaut oder {@code null}
     * @param kind      Art nach Form
     * @param basis     {@link Basis#PARSER} oder {@link Basis#CASE_HEURISTIC}
     */
    public record Attachment(String id, String head, String dependent, String relation, String marker,
                             AttachmentKind kind, Basis basis) {

        public Attachment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(dependent, "dependent");
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(basis, "basis");
        }
    }

    public enum AttachmentKind {
        GENITIVE,
        PREPOSITIONAL,
        OTHER
    }
}
