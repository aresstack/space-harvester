package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Graph-Projektion einer Seitenanalyse (#499, ADR 0024): Knoten für Dokument, Erwähnungen und Aussagen, typisierte
 * Kanten mit Herkunft, Beleg und Sicherheit. Deterministisch aus {@link PageStatementAnalysis} abgeleitet und deshalb
 * nicht eigens gespeichert: Ersetzt eine neue Analyse die alte, ersetzt sie damit auch die Projektion, ohne verwaiste
 * Knoten oder Kanten.
 *
 * @param key   Seite
 * @param nodes Knoten; das Dokument zuerst
 * @param edges Kanten
 */
public record PageGraph(PageKey key, List<Node> nodes, List<Edge> edges) {

    public PageGraph {
        Objects.requireNonNull(key, "key");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** Art eines Knotens. */
    public enum NodeKind {
        /** Die analysierte Fassung einer Seite. */
        DOCUMENT,
        /** Eine Erwähnung an genau einer Textstelle. */
        MENTION,
        /** Eine Aussage (Prädikat mit Beteiligten). */
        STATEMENT
    }

    /**
     * Ein Knoten.
     *
     * @param id    eindeutig über alle Seiten: Dokument {@code quelle/seite@fassung}, sonst die ID der Erwähnung bzw.
     *              Aussage
     * @param kind  Art
     * @param label Wortlaut (Erwähnung, Prädikat) oder Titel (Dokument)
     * @param lemma Grundform oder {@code null}
     * @param start Beginn im Text oder -1 beim Dokument
     * @param end   Ende im Text oder -1 beim Dokument
     */
    public record Node(String id, NodeKind kind, String label, String lemma, int start, int end) {

        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Art einer Kante. Die Rollen entsprechen {@link ParsedStatements.Role}. */
    public enum EdgeKind {
        /** Dokument → Aussage bzw. Erwähnung. */
        CONTAINS,
        ACTOR,
        AFFECTED_OBJECT,
        RECIPIENT,
        PREPOSITIONAL_OBJECT,
        INSTRUMENT,
        TIME,
        CIRCUMSTANCE,
        /** Nominaler Bezug ({@code Beleg} → {@code des Kunden}); kein Besitz. */
        NOMINAL
    }

    /**
     * Eine Kante.
     *
     * @param from     Quellknoten
     * @param to       Zielknoten
     * @param kind     Art
     * @param origin   Herkunft: {@code STRUCTURE} beim Enthaltensein, sonst {@link ParsedStatements.Basis}
     * @param relation UD-Relation ({@code nsubj}, {@code obl:arg}, {@code nmod}) oder {@code null}
     * @param marker   Präposition im Wortlaut oder {@code null}
     * @param evidence Beleg: Satzindex als {@code s<Satz>}, beim Enthaltensein {@code null}
     * @param certain  ob die Zuordnung sicher ist
     */
    public record Edge(String from, String to, EdgeKind kind, String origin, String relation, String marker,
                       String evidence, boolean certain) {

        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /** Qualitätsstufen der Standardansicht: nur plausible Aussagen (Nachtrag Ursachenmatrix zu ADR 0024). */
    public static final Set<ParsedStatements.Quality> DEFAULT_QUALITIES = Set.of(ParsedStatements.Quality.PLAUSIBLE);

    /** Die Standardprojektion: nur {@link ParsedStatements.Quality#PLAUSIBLE plausible} Aussagen. */
    public static PageGraph of(PageStatementAnalysis analysis) {
        return of(analysis, DEFAULT_QUALITIES);
    }

    /**
     * Leitet die Projektion ab; Aussagen anderer Qualitätsstufen und Beteiligte auf unbekannte Erwähnungen werden
     * übergangen. Erwähnungen und nominale Bezüge bleiben vollständig, sie sind Text, keine Relation.
     */
    public static PageGraph of(PageStatementAnalysis analysis, Set<ParsedStatements.Quality> qualities) {
        String document = analysis.documentId();
        ParsedStatements result = analysis.result();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        nodes.add(new Node(document, NodeKind.DOCUMENT, analysis.title(), null, -1, -1));
        Map<String, Integer> mentions = new HashMap<>();
        for (ParsedStatements.Mention mention : result.mentions()) {
            if (mentions.putIfAbsent(mention.id(), mention.sentence()) == null) {
                nodes.add(new Node(mention.id(), NodeKind.MENTION, mention.text(), mention.lemma(), mention.start(),
                        mention.end()));
                edges.add(new Edge(document, mention.id(), EdgeKind.CONTAINS, "STRUCTURE", null, null, null, true));
            }
        }
        for (ParsedStatements.Statement statement : result.statements()) {
            if (!qualities.contains(statement.quality())) {
                continue;
            }
            nodes.add(new Node(statement.id(), NodeKind.STATEMENT, statement.predicateText(),
                    statement.predicateLemma(), statement.start(), statement.end()));
            edges.add(new Edge(document, statement.id(), EdgeKind.CONTAINS, "STRUCTURE", null, null, null, true));
            for (ParsedStatements.Argument argument : statement.arguments()) {
                if (mentions.containsKey(argument.mention())) {
                    edges.add(new Edge(statement.id(), argument.mention(), EdgeKind.valueOf(argument.role().name()),
                            argument.basis().name(), argument.relation(), argument.marker(),
                            "s" + statement.sentence(), argument.certain()));
                }
            }
        }
        for (ParsedStatements.Attachment attachment : result.attachments()) {
            if (mentions.containsKey(attachment.head()) && mentions.containsKey(attachment.dependent())) {
                edges.add(new Edge(attachment.head(), attachment.dependent(), EdgeKind.NOMINAL,
                        attachment.basis().name(), attachment.relation(), attachment.marker(),
                        "s" + mentions.get(attachment.head()),
                        attachment.basis() == ParsedStatements.Basis.PARSER));
            }
        }
        return new PageGraph(analysis.key(), nodes, edges);
    }

    /** Die Kanten, die an diesem Knoten beginnen oder enden. */
    public List<Edge> edgesOf(String nodeId) {
        return edges.stream().filter(edge -> edge.from().equals(nodeId) || edge.to().equals(nodeId)).toList();
    }
}
