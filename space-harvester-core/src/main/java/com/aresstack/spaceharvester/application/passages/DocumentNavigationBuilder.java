package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Baut die Dokumentnavigation einer Seite (FA-01 bis FA-05), kopiert aus dem Enterprise AI Client
 * ({@code DocumentNavigationBuilder}, #181, ADR 0020). Die Struktur geht vor: Die Überschriften der Seite bilden die
 * Hierarchie und bleiben unverändert. Semantisch verfeinert wird nur der Fließtext eines Bereichs (vor der ersten
 * Unterüberschrift bzw. die ganze Seite ohne Überschriften), und nur ab {@value #MIN_TOPIC_PASSAGES} Absätzen: Die
 * Absätze gruppiert {@link TopicClusterer}; aufeinanderfolgende Absätze desselben Themas bilden einen
 * Themenabschnitt. Kehrt ein Thema später wieder, entsteht dort ein eigener Abschnitt; die Navigation verliert nie die
 * Lesereihenfolge (FA-03).
 *
 * <p>Bezeichnungen der Themenabschnitte kommen aus der Analyse (erster Satz der repräsentativen Passage des Themas);
 * ein {@link SectionLabeler} darf sie ersetzen. Ein einzelnes oberstes Kapitel, das die ganze Seite umschließt, wird
 * aufgelöst: seine Unterkapitel werden die Hauptkapitel.
 *
 * <p>Anders als im Client sind die Absätze schon Stellen {@code [start, end)} im selben Text; das Wiederfinden über
 * {@code TextLocator} entfällt. Die Überschriften kommen aus {@link PageText#sections()} statt aus Markdown.
 */
public final class DocumentNavigationBuilder {

    /** Ab so vielen Absätzen lohnt eine Unterteilung eines Bereichs in Themenabschnitte. */
    public static final int MIN_TOPIC_PASSAGES = 3;
    static final int ANCHOR_CHARS = 60;

    private final TopicClusterer clusterer;

    public DocumentNavigationBuilder() {
        this(new TopicClusterer());
    }

    public DocumentNavigationBuilder(TopicClusterer clusterer) {
        this.clusterer = Objects.requireNonNull(clusterer, "clusterer");
    }

    /** Version des Verfahrens samt Grenzwerten. */
    public String fingerprint() {
        return "navigation-v1;min=" + MIN_TOPIC_PASSAGES + ";cutoff=" + TopicClusterer.DEFAULT_MERGE_DISTANCE_CUTOFF
                + ";representatives=" + TopicClusterer.DEFAULT_REPRESENTATIVES;
    }

    /** Ein Absatz mit seinem Vektor; {@code [start, end)} im Text. */
    public record Passage(int start, int end, float[] vector) {

        public Passage {
            Objects.requireNonNull(vector, "vector");
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Ungültiger Absatz");
            }
        }

        String id() {
            return "p" + start;
        }
    }

    /**
     * Das Ergebnis.
     *
     * @param analysis ob Themenabschnitte erkannt wurden
     * @param reason   ohne Themenabschnitte warum, sonst {@code null}
     */
    public record Navigation(Analysis analysis, String reason, List<NavigationSection> chapters) {

        public Navigation {
            Objects.requireNonNull(analysis, "analysis");
            chapters = List.copyOf(chapters);
        }
    }

    /** Wie die Navigation entstand. */
    public enum Analysis {
        /** Nur die Überschriften der Seite (FA-01). */
        HEADINGS,
        /** Überschriften und erkannte Themenabschnitte. */
        SEMANTIC
    }

    /** Nur Originalüberschriften (Rückfall, FA-01). */
    public Navigation headingsOnly(PageText text, String reason) {
        Context context = new Context(text.text());
        List<NavigationSection> chapters = new ArrayList<>();
        for (Node node : promote(tree(context, text.sections()))) {
            chapters.add(toSection(context, node, 1, null, Map.of()));
        }
        return new Navigation(Analysis.HEADINGS, reason, chapters);
    }

    /**
     * Originalüberschriften plus Themenabschnitte aus den Absätzen.
     *
     * @param passages die Absätze in Lesereihenfolge
     * @param labeler  benennt Themenabschnitte oder {@code null}
     */
    public Navigation build(String title, PageText text, List<Passage> passages, SectionLabeler labeler) {
        Context context = new Context(text.text());
        List<Node> roots = tree(context, text.sections());
        List<Node> top = promote(roots);

        // Jeden Absatz dem tiefsten Bereich zuordnen, in dessen Fließtext er beginnt.
        Map<Node, List<Passage>> byNode = new IdentityHashMap<>();
        List<Passage> preamble = new ArrayList<>();
        for (Passage passage : passages) {
            if (passage.end() > context.text.length()) {
                continue;
            }
            Node owner = bodyOwner(roots, passage.start());
            if (owner == null) {
                preamble.add(passage);
            } else {
                byNode.computeIfAbsent(owner, node -> new ArrayList<>()).add(passage);
            }
        }

        // Themenabschnitte je Bereich; der Text je Abschnitt geht an den Benenner.
        List<TopicRun> allRuns = new ArrayList<>();
        Map<Node, List<TopicRun>> runsByNode = new IdentityHashMap<>();
        List<Node> wrappers = wrappers(roots);
        for (Node node : nodesInOrder(roots)) {
            List<Passage> own = byNode.get(node);
            if (own == null) {
                continue;
            }
            // Fließtext eines aufgelösten Kapitels steht wie Vortext vor den Hauptkapiteln.
            if (wrappers.contains(node)) {
                preamble.addAll(own);
                continue;
            }
            List<TopicRun> runs = topicRuns(context, own, false);
            runsByNode.put(node, runs);
            allRuns.addAll(runs);
        }
        preamble.sort(Comparator.comparingInt(Passage::start));
        List<TopicRun> preambleRuns = topicRuns(context, preamble, !roots.isEmpty());
        allRuns.addAll(preambleRuns);
        applyLabels(title, context, allRuns, labeler);

        Map<Node, List<NavigationSection>> topicSections = new IdentityHashMap<>();
        runsByNode.forEach((node, runs) -> topicSections.put(node,
                runs.stream().map(run -> run.toSection(context, 1, null)).toList()));
        List<NavigationSection> chapters = new ArrayList<>();
        for (TopicRun run : preambleRuns) {
            chapters.add(run.toSection(context, 1, null));
        }
        for (Node node : top) {
            chapters.add(toSection(context, node, 1, null, topicSections));
        }
        boolean semantic = !allRuns.isEmpty();
        return new Navigation(semantic ? Analysis.SEMANTIC : Analysis.HEADINGS,
                semantic ? null : NavigationPayload.REASON_NO_TOPIC_CHANGE, chapters);
    }

    // ------------------------------------------------------------------ Überschriften-Baum

    private record Context(String text) {

        /** Textanfang an {@code start}, höchstens {@code max} Zeichen, bis zum ersten Zeilenumbruch. */
        String anchorAt(int start, int max) {
            return NavigationPayload.anchorAt(text, start, max);
        }
    }

    private static final class Node {
        final int ordinal;
        final PageText.Section heading;
        final List<Node> children = new ArrayList<>();
        int end;

        Node(int ordinal, PageText.Section heading) {
            this.ordinal = ordinal;
            this.heading = heading;
        }

        /** Ende des eigenen Fließtexts: erste Unterüberschrift oder Bereichsende. */
        int bodyEnd() {
            return children.isEmpty() ? end : children.getFirst().heading.start();
        }
    }

    private static List<Node> tree(Context context, List<PageText.Section> headings) {
        List<Node> roots = new ArrayList<>();
        List<Node> stack = new ArrayList<>();
        int ordinal = 0;
        for (PageText.Section heading : headings) {
            Node node = new Node(ordinal++, heading);
            while (!stack.isEmpty() && stack.getLast().heading.level() >= heading.level()) {
                stack.removeLast().end = heading.start();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.getLast().children.add(node);
            }
            stack.add(node);
        }
        for (Node open : stack) {
            open.end = context.text.length();
        }
        return roots;
    }

    /** Ein einziges oberstes Kapitel mit Unterkapiteln umschließt die Seite: seine Kinder werden Hauptkapitel. */
    private static List<Node> promote(List<Node> roots) {
        List<Node> top = roots;
        while (top.size() == 1 && !top.getFirst().children.isEmpty()) {
            top = top.getFirst().children;
        }
        return top;
    }

    /** Die Kapitel, die {@link #promote} auflöst. */
    private static List<Node> wrappers(List<Node> roots) {
        List<Node> wrappers = new ArrayList<>();
        List<Node> top = roots;
        while (top.size() == 1 && !top.getFirst().children.isEmpty()) {
            wrappers.add(top.getFirst());
            top = top.getFirst().children;
        }
        return wrappers;
    }

    private static List<Node> nodesInOrder(List<Node> nodes) {
        List<Node> ordered = new ArrayList<>();
        for (Node node : nodes) {
            ordered.add(node);
            ordered.addAll(nodesInOrder(node.children));
        }
        return ordered;
    }

    /** Der tiefste Bereich, in dessen eigenem Fließtext {@code position} liegt; {@code null}: vor der ersten Überschrift. */
    private static Node bodyOwner(List<Node> nodes, int position) {
        for (Node node : nodes) {
            if (position >= node.heading.start() && position < node.end) {
                if (position < node.bodyEnd()) {
                    return node;
                }
                Node inner = bodyOwner(node.children, position);
                return inner == null ? node : inner;
            }
        }
        return null;
    }

    private NavigationSection toSection(Context context, Node node, int level, String parentId,
                                        Map<Node, List<NavigationSection>> topics) {
        String id = "h" + node.ordinal;
        List<NavigationSection> children = new ArrayList<>();
        for (NavigationSection topic : topics.getOrDefault(node, List.of())) {
            children.add(topic.placed(level + 1, id));
        }
        for (Node child : node.children) {
            children.add(toSection(context, child, level + 1, id, topics));
        }
        return new NavigationSection(id, node.heading.heading(), NavigationSection.Origin.HEADING, level,
                node.heading.start(), node.end, node.heading.heading(), parentId, children);
    }

    // ------------------------------------------------------------------ Themenabschnitte

    /** Aufeinanderfolgende Absätze eines Themas. */
    private static final class TopicRun {
        final List<Passage> members = new ArrayList<>();
        String label;

        String text(Context context) {
            StringBuilder text = new StringBuilder();
            for (Passage member : members) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(context.text.substring(member.start(), member.end()).strip());
            }
            return text.toString();
        }

        NavigationSection toSection(Context context, int level, String parentId) {
            Passage first = members.getFirst();
            Passage last = members.getLast();
            return new NavigationSection(first.id(), label, NavigationSection.Origin.TOPIC, level, first.start(),
                    Math.max(first.start(), last.end()), context.anchorAt(first.start(), ANCHOR_CHARS), parentId,
                    null);
        }
    }

    /**
     * Themenabschnitte eines Bereichs: Clustering, dann Läufe gleicher Themen in Lesereihenfolge. Ein Bereich mit nur
     * einem Lauf bleibt ungeteilt, außer {@code keepSingle} (Text vor der ersten Überschrift, sonst nicht anspringbar).
     */
    private List<TopicRun> topicRuns(Context context, List<Passage> located, boolean keepSingle) {
        if (located.isEmpty() || (located.size() < MIN_TOPIC_PASSAGES && !keepSingle)) {
            return List.of();
        }
        List<TopicClusterer.Member> members = new ArrayList<>();
        for (Passage item : located) {
            members.add(new TopicClusterer.Member(item.id(), context.text.substring(item.start(), item.end()).strip(),
                    item.vector()));
        }
        // Unter der Mindestzahl (nur Vortext) alle Absätze als ein Lauf: ein Lauf je Absatz wäre zu fein.
        List<TopicClusterer.Topic> topics = located.size() < MIN_TOPIC_PASSAGES ? List.of()
                : clusterer.cluster(members);
        Map<String, TopicClusterer.Topic> topicOf = new HashMap<>();
        for (TopicClusterer.Topic topic : topics) {
            for (String member : topic.memberIds()) {
                topicOf.put(member, topic);
            }
        }
        List<TopicRun> runs = new ArrayList<>();
        TopicRun current = null;
        TopicClusterer.Topic currentTopic = null;
        for (Passage item : located) {
            TopicClusterer.Topic topic = topicOf.get(item.id());
            if (current == null || topic != currentTopic) {
                current = new TopicRun();
                runs.add(current);
                currentTopic = topic;
            }
            current.members.add(item);
        }
        if (runs.size() < 2 && !keepSingle) {
            return List.of();
        }
        for (TopicRun run : runs) {
            run.label = analysisLabel(context, run, topicOf);
        }
        return runs;
    }

    /** Erster Satz des Repräsentanten des Themas, sofern er in diesem Lauf liegt. */
    private static String analysisLabel(Context context, TopicRun run, Map<String, TopicClusterer.Topic> topicOf) {
        Passage first = run.members.getFirst();
        TopicClusterer.Topic topic = topicOf.get(first.id());
        if (topic != null) {
            for (String representative : topic.representativeIds()) {
                for (Passage member : run.members) {
                    if (member.id().equals(representative)) {
                        return firstSentence(context.text.substring(member.start(), member.end()));
                    }
                }
            }
        }
        return firstSentence(context.text.substring(first.start(), first.end()));
    }

    /** Bis zum ersten Punkt, höchstens 80 Zeichen. */
    static String firstSentence(String text) {
        String trimmed = text.strip();
        int end = trimmed.indexOf('.');
        String head = end > 0 ? trimmed.substring(0, end) : trimmed;
        return head.length() <= 80 ? head : head.substring(0, 80);
    }

    private static void applyLabels(String title, Context context, List<TopicRun> runs, SectionLabeler labeler) {
        if (runs.isEmpty() || labeler == null) {
            return;
        }
        List<String> labels;
        try {
            labels = labeler.labels(title, runs.stream().map(run -> run.text(context)).toList());
        } catch (RuntimeException failed) {
            return; // Benennung ist Kür: die Bezeichnungen der Analyse bleiben
        }
        if (labels == null) {
            return;
        }
        for (int i = 0; i < runs.size() && i < labels.size(); i++) {
            String label = labels.get(i);
            if (label != null && !label.isBlank()) {
                runs.get(i).label = label.strip();
            }
        }
    }
}
