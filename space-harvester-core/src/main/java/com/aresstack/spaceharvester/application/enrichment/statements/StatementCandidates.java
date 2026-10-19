package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.List;

/**
 * Was ein {@link StatementExtractor} für einen {@link ParagraphText} vorschlägt, vor jeder Prüfung: lokale Schlüssel,
 * Satznummern und Wortbereiche, wie der Extraktor sie angab. Nichts davon gilt als sicher; der
 * {@link StatementValidator} entscheidet.
 *
 * @param mentions  Erwähnungen
 * @param events    Handlungen
 * @param relations Eigenschaften
 * @param orders    Folgen von Handlungen
 * @param malformed Einträge in unlesbarer Form (beim Lesen verworfen)
 */
public record StatementCandidates(List<MentionCandidate> mentions, List<EventCandidate> events,
                                  List<RelationCandidate> relations, List<OrderCandidate> orders, int malformed) {

    public StatementCandidates {
        mentions = copy(mentions);
        events = copy(events);
        relations = copy(relations);
        orders = copy(orders);
        malformed = Math.max(0, malformed);
    }

    public static StatementCandidates empty() {
        return new StatementCandidates(null, null, null, null, 0);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * Ein Wortbereich in einem Satz: ab 1, einschließlich, mit dem Text, den der Extraktor dort las.
     *
     * @param first erstes Wort ab 1 oder {@code 0}, wenn nur der Text bekannt ist
     * @param last  letztes Wort
     * @param text  Wortlaut, nie {@code null}
     */
    public record Words(int first, int last, String text) {

        public Words {
            text = text == null ? "" : text;
        }

        @Override
        public String toString() {
            return first + "-" + last;
        }
    }

    /** Verneinung, Modalität und Bedingung einer Aussage, je als Wörter ihres Satzes ({@code null}: keine). */
    public record Context(Words negation, Words modality, Words condition) {

        public static Context none() {
            return new Context(null, null, null);
        }
    }

    /**
     * Eine Nominalgruppe (oder ein Pronomen), die der Extraktor fand.
     *
     * @param lemma vorgeschlagene Grundform oder {@code null}
     */
    public record MentionCandidate(String key, int sentence, Words words, String lemma, boolean pronoun) {
    }

    /** Ein Beteiligter einer Handlung: eine Rolle und der Schlüssel einer Erwähnung. */
    public record ParticipantCandidate(String role, String mention) {
    }

    /**
     * Eine Handlung mit Prädikat (und der abgetrennten Partikel eines trennbaren Verbs).
     *
     * @param particle abgetrennte Partikel („aus“ in „löst … aus“) oder {@code null}
     */
    public record EventCandidate(String key, int sentence, Words predicate, Words particle, String lemma,
                                 List<ParticipantCandidate> participants, Context context) {

        public EventCandidate {
            participants = copy(participants);
            context = context == null ? Context.none() : context;
        }
    }

    /** Eine Eigenschaft einer Erwähnung, gegeben durch Wörter desselben Satzes. */
    public record RelationCandidate(String key, int sentence, String kind, String subject, Words value, String lemma,
                                    Context context) {

        public RelationCandidate {
            context = context == null ? Context.none() : context;
        }
    }

    /** Eine Handlung vor einer anderen, nach einem Signalwort im Satz. */
    public record OrderCandidate(String before, String after, int sentence, Words cue) {
    }
}
