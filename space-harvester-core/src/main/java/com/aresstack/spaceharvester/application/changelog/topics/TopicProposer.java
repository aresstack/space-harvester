package com.aresstack.spaceharvester.application.changelog.topics;

import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Nature;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Occurrence;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * Bildet aus Änderungseinheiten deterministische Themenvorschläge (ADR 0030, Nachtrag Abschnitt 4). Eine reine
 * Funktion: Vektoren, Verweise und Aussagen kommen fertig herein ({@link Inputs}); kein Aufrufer, keine Ablage, kein
 * KI-Dienst.
 *
 * <p>Zweistufig, damit große Updates beherrschbar bleiben: Erst werden die Einheiten je Seite und Abschnitt zu Bündeln
 * zusammengelegt, dann die Bündel agglomerativ mit Average Linkage gruppiert (Nearest-Neighbour-Chain, quadratisch in
 * der Zahl der Bündel). Höchstens {@link TopicWeights#maxBundles()} Bündel werden gruppiert; die leichtesten übrigen
 * stehen als Rest da. Kleine Korrekturen gehen in kein Thema, sie werden gezählt.
 *
 * <p>Kante zwischen zwei Bündeln = gewichtetes Mittel der Signale, die für beide vorliegen ({@link Signal}); Zeit und
 * Bearbeiter sind kein Signal. Gleichstände entscheidet die feste Reihenfolge der Einheiten.
 */
public final class TopicProposer {

    /** Verfahren und Version. */
    public static final String METHOD = "change-topics/v1";
    /** Repräsentanten je Thema. */
    static final int REPRESENTATIVES = 2;
    /** Höchstens so viele Bündelpaare je Thema für die tragenden Signale. */
    static final int SIGNAL_PAIRS = 5_000;

    private final TopicWeights weights;

    public TopicProposer(TopicWeights weights) {
        this.weights = Objects.requireNonNull(weights, "weights");
    }

    /**
     * Vorverarbeitete Angaben neben den Einheiten.
     *
     * @param modelId    Kennung des Embedding-Modells oder {@code null} ohne Vektoren
     * @param vectors    Vektor je Kennung einer Einheit, nur für freigegebene Einheiten
     * @param references ob eine Seite auf eine andere verweist; {@code null}, wenn unbekannt (Signal fehlt)
     * @param objects    Objekte der Aussagen je Einheit; {@code null} ohne Satzanalysator (Signal fehlt)
     * @param statements Aussagen-Unterschiede je Einheit; leer ohne Satzanalysator
     */
    public record Inputs(String modelId, Map<String, float[]> vectors, BiPredicate<PageKey, PageKey> references,
                         Map<String, Set<String>> objects, List<StatementDelta> statements) {

        public static final Inputs NONE = new Inputs(null, Map.of(), null, null, List.of());

        public Inputs {
            vectors = Map.copyOf(vectors);
            objects = objects == null ? null : Map.copyOf(objects);
            statements = List.copyOf(statements);
        }
    }

    /**
     * @param sourceId Quelle
     * @param updateId Update
     * @param spaceKey Ebene: leer für die Quelle, sonst der Space (die Einheiten sind schon darauf beschränkt)
     * @param units    Ergebnis von {@link ChangeUnits#build}
     */
    public TopicSet propose(String sourceId, String updateId, Optional<String> spaceKey, ChangeUnits.Result units,
                            Inputs inputs) {
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(inputs, "inputs");
        Map<String, float[]> vectors = released(units.units(), inputs.vectors());
        List<ChangeUnit> released = units.units().stream().filter(ChangeUnit::aiReleased).toList();
        Set<Signal> available = available(released, vectors, inputs);
        int corrections = 0;
        int notReleased = 0;
        Map<String, Bundle> byKey = new LinkedHashMap<>();
        for (ChangeUnit unit : units.units()) {
            if (!unit.aiReleased()) {
                // Ohne KI-Freigabe in keinem Thema (Auflage Q/AT); nur gezählt.
                notReleased++;
                continue;
            }
            if (unit.nature() == Nature.CORRECTION) {
                corrections++;
                continue;
            }
            byKey.computeIfAbsent(bundleKey(unit), ignored -> new Bundle()).add(unit, vectors.get(unit.id()),
                    inputs.objects() == null ? null : inputs.objects().get(unit.id()));
        }
        List<Bundle> bundles = new ArrayList<>(byKey.values());
        bundles.forEach(Bundle::finish);
        List<String> ungrouped = new ArrayList<>();
        if (bundles.size() > weights.maxBundles()) {
            // Die schwersten Bündel werden gruppiert; die übrigen stehen als Rest da, in fester Reihenfolge.
            Map<Bundle, Integer> position = new java.util.IdentityHashMap<>();
            for (int i = 0; i < bundles.size(); i++) {
                position.put(bundles.get(i), i);
            }
            List<Bundle> byWeight = new ArrayList<>(bundles);
            byWeight.sort(Comparator.comparingDouble((Bundle bundle) -> -bundle.weight)
                    .thenComparingInt(position::get));
            Set<Bundle> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            kept.addAll(byWeight.subList(0, weights.maxBundles()));
            List<Bundle> grouped = new ArrayList<>();
            for (Bundle bundle : bundles) {
                if (kept.contains(bundle)) {
                    grouped.add(bundle);
                } else {
                    bundle.units.forEach(unit -> ungrouped.add(unit.id()));
                }
            }
            bundles = grouped;
        }
        int[] cluster = cluster(bundles, inputs);
        Map<Integer, List<Bundle>> groups = new TreeMap<>();
        for (int i = 0; i < bundles.size(); i++) {
            groups.computeIfAbsent(cluster[i], ignored -> new ArrayList<>()).add(bundles.get(i));
        }
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < units.units().size(); i++) {
            order.put(units.units().get(i).id(), i);
        }
        List<ChangeTopic> topics = new ArrayList<>();
        for (List<Bundle> group : groups.values()) {
            topics.add(topic(group, order, vectors, inputs));
        }
        topics.sort(Comparator.comparingDouble((ChangeTopic topic) -> -topic.weight()).thenComparing(ChangeTopic::id));
        return new TopicSet(sourceId, updateId, spaceKey, METHOD, inputSha256(units.units(), available, inputs),
                available, missing(released, available), topics, units.units(), corrections,
                units.notEvaluated(), notReleased, ungrouped);
    }

    /**
     * Signale mit Gewicht, die nicht oder nur eingeschränkt vorliegen: nicht verfügbare Signale, dazu
     * {@link Signal#SECTION}, wenn keine Einheit einen Überschriftenpfad hat (dann nur Seite und Elternseite).
     */
    private Set<Signal> missing(List<ChangeUnit> released, Set<Signal> available) {
        Set<Signal> missing = EnumSet.noneOf(Signal.class);
        for (Signal signal : Signal.values()) {
            if (weights.weight(signal) > 0 && !available.contains(signal)) {
                missing.add(signal);
            }
        }
        if (weights.weight(Signal.SECTION) > 0 && released.stream()
                .noneMatch(unit -> unit.occurrences().stream().anyMatch(o -> !o.headingPath().isEmpty()))) {
            missing.add(Signal.SECTION);
        }
        return missing;
    }

    /** Vektoren nur freigegebener Einheiten; was sonst hereinkommt, wird übergangen. */
    private static Map<String, float[]> released(List<ChangeUnit> units, Map<String, float[]> vectors) {
        Map<String, float[]> released = new HashMap<>();
        for (ChangeUnit unit : units) {
            float[] vector = vectors.get(unit.id());
            if (unit.aiReleased() && vector != null && vector.length > 0) {
                released.put(unit.id(), vector);
            }
        }
        return released;
    }

    private Set<Signal> available(List<ChangeUnit> units, Map<String, float[]> vectors, Inputs inputs) {
        Set<Signal> available = EnumSet.noneOf(Signal.class);
        if (!vectors.isEmpty()) {
            available.add(Signal.VECTOR);
        }
        available.add(Signal.SECTION);
        if (units.stream().anyMatch(unit -> !unit.terms().isEmpty())) {
            available.add(Signal.TERMS);
        }
        if (inputs.references() != null) {
            available.add(Signal.REFERENCES);
        }
        if (inputs.objects() != null) {
            available.add(Signal.STATEMENTS);
        }
        available.removeIf(signal -> weights.weight(signal) <= 0);
        return available;
    }

    /** Stufe 1: je Seite der ersten Fundstelle und Abschnitt ein Bündel. */
    private static String bundleKey(ChangeUnit unit) {
        Occurrence first = unit.first();
        return first.page().sourceId() + "\u0000" + first.page().pageId() + "\u0000"
                + String.join("\u0001", first.headingPath());
    }

    /** Stufe 2: Average Linkage über die Bündel per Nearest-Neighbour-Chain; Ergebnis: Gruppe je Bündel. */
    private int[] cluster(List<Bundle> bundles, Inputs inputs) {
        int n = bundles.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        if (n < 2) {
            return parent;
        }
        float[] distance = new float[n * n];
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                float d = (float) distance(bundles.get(a), bundles.get(b), inputs);
                distance[a * n + b] = d;
                distance[b * n + a] = d;
            }
        }
        boolean[] active = new boolean[n];
        java.util.Arrays.fill(active, true);
        int[] size = new int[n];
        java.util.Arrays.fill(size, 1);
        List<double[]> merges = new ArrayList<>(); // {Höhe, a, b, Reihenfolge}
        int[] chain = new int[n];
        int top = 0;
        int remaining = n;
        while (remaining > 1) {
            if (top == 0) {
                for (int i = 0; i < n; i++) {
                    if (active[i]) {
                        chain[top++] = i;
                        break;
                    }
                }
            }
            int a = chain[top - 1];
            int previous = top >= 2 ? chain[top - 2] : -1;
            int best = previous;
            float bestDistance = previous >= 0 ? distance[a * n + previous] : Float.MAX_VALUE;
            for (int x = 0; x < n; x++) {
                if (!active[x] || x == a) {
                    continue;
                }
                float d = distance[a * n + x];
                if (d < bestDistance) {
                    bestDistance = d;
                    best = x;
                }
            }
            if (best == previous) {
                top -= 2;
                int keep = Math.min(a, best);
                int drop = Math.max(a, best);
                merges.add(new double[] {bestDistance, keep, drop, merges.size()});
                for (int k = 0; k < n; k++) {
                    if (active[k] && k != keep && k != drop) {
                        float d = (float) ((size[keep] * (double) distance[keep * n + k]
                                + size[drop] * (double) distance[drop * n + k]) / (size[keep] + size[drop]));
                        distance[keep * n + k] = d;
                        distance[k * n + keep] = d;
                    }
                }
                size[keep] += size[drop];
                active[drop] = false;
                remaining--;
            } else {
                chain[top++] = best;
            }
        }
        // Average Linkage ist reduzibel: Verschmelzungen bis zur Grenze hängen nie von höheren ab.
        merges.sort(Comparator.comparingDouble((double[] merge) -> merge[0]).thenComparingDouble(merge -> merge[3]));
        for (double[] merge : merges) {
            if (merge[0] > weights.cutoff()) {
                break;
            }
            int a = find(parent, (int) merge[1]);
            int b = find(parent, (int) merge[2]);
            if (a != b) {
                parent[Math.max(a, b)] = Math.min(a, b);
            }
        }
        int[] cluster = new int[n];
        for (int i = 0; i < n; i++) {
            cluster[i] = find(parent, i);
        }
        return cluster;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Kombinierte Distanz: 1 − gewichtetes Mittel der für beide vorliegenden Signale. */
    double distance(Bundle a, Bundle b, Inputs inputs) {
        double sum = 0;
        double total = 0;
        for (Map.Entry<Signal, Double> signal : similarities(a, b, inputs).entrySet()) {
            double weight = weights.weight(signal.getKey());
            sum += weight * signal.getValue();
            total += weight;
        }
        return total <= 0 ? 1.0 : 1.0 - sum / total;
    }

    /** Die Signale, die für beide Bündel vorliegen und ein Gewicht haben, mit ihrer Ähnlichkeit. */
    private Map<Signal, Double> similarities(Bundle a, Bundle b, Inputs inputs) {
        Map<Signal, Double> found = new EnumMap<>(Signal.class);
        if (weights.weight(Signal.VECTOR) > 0 && a.vector != null && b.vector != null) {
            found.put(Signal.VECTOR, Math.max(0, cosine(a.vector, b.vector)));
        }
        if (weights.weight(Signal.SECTION) > 0) {
            found.put(Signal.SECTION, section(a, b));
        }
        if (weights.weight(Signal.TERMS) > 0 && !a.terms.isEmpty() && !b.terms.isEmpty()) {
            found.put(Signal.TERMS, jaccard(a.terms, b.terms));
        }
        if (weights.weight(Signal.REFERENCES) > 0 && inputs.references() != null) {
            found.put(Signal.REFERENCES, references(a, b, inputs.references()) ? 1.0 : 0.0);
        }
        if (weights.weight(Signal.STATEMENTS) > 0 && a.objects != null && b.objects != null
                && !a.objects.isEmpty() && !b.objects.isEmpty()) {
            found.put(Signal.STATEMENTS, jaccard(a.objects, b.objects));
        }
        return found;
    }

    /**
     * Gleiche Seite und gleicher Abschnitt 1, gleiche Seite 0,7, Eltern- und Kindseite oder Geschwister 0,5,
     * gemeinsamer Teilbaum (zwei Vorfahren) 0,25, sonst 0.
     */
    static double section(Bundle a, Bundle b) {
        for (PageKey page : a.pages) {
            if (b.pages.contains(page)) {
                return !a.headingPath.isEmpty() && a.headingPath.equals(b.headingPath) ? 1.0 : 0.7;
            }
        }
        for (String parent : a.parents) {
            if (b.parents.contains(parent)) {
                return 0.5;
            }
        }
        for (PageKey page : a.pages) {
            if (b.parents.contains(page.pageId())) {
                return 0.5;
            }
        }
        for (PageKey page : b.pages) {
            if (a.parents.contains(page.pageId())) {
                return 0.5;
            }
        }
        for (List<String> path : a.paths) {
            for (List<String> other : b.paths) {
                if (path.size() >= 2 && other.size() >= 2 && path.subList(0, 2).equals(other.subList(0, 2))) {
                    return 0.25;
                }
            }
        }
        return 0.0;
    }

    private static boolean references(Bundle a, Bundle b, BiPredicate<PageKey, PageKey> references) {
        for (PageKey from : a.pages) {
            for (PageKey to : b.pages) {
                if (!from.equals(to) && (references.test(from, to) || references.test(to, from))) {
                    return true;
                }
            }
        }
        return false;
    }

    private ChangeTopic topic(List<Bundle> group, Map<String, Integer> order, Map<String, float[]> vectors,
                              Inputs inputs) {
        List<ChangeUnit> members = new ArrayList<>();
        group.forEach(bundle -> members.addAll(bundle.units));
        members.sort(Comparator.comparingInt(unit -> order.get(unit.id())));
        List<String> ids = members.stream().map(ChangeUnit::id).toList();
        String id = ids.stream().min(Comparator.naturalOrder()).orElseThrow();
        Set<String> spaces = new java.util.TreeSet<>();
        Set<PageKey> pages = new LinkedHashSet<>();
        Instant from = null;
        Instant to = null;
        Map<Nature, Integer> natures = new EnumMap<>(Nature.class);
        double weight = 0;
        for (ChangeUnit unit : members) {
            natures.merge(unit.nature(), 1, Integer::sum);
            weight += weight(unit);
            for (Occurrence occurrence : unit.occurrences()) {
                if (occurrence.spaceKey() != null) {
                    spaces.add(occurrence.spaceKey());
                }
                pages.add(occurrence.page());
                if (occurrence.at() != null) {
                    from = from == null || occurrence.at().isBefore(from) ? occurrence.at() : from;
                    to = to == null || occurrence.at().isAfter(to) ? occurrence.at() : to;
                }
            }
        }
        Set<String> memberIds = new java.util.HashSet<>(ids);
        List<StatementDelta> statements = inputs.statements().stream()
                .filter(delta -> memberIds.contains(delta.unitId())).toList();
        return new ChangeTopic(id, ids, representatives(members, vectors), label(members), weight, spaces,
                List.copyOf(pages), from, to, 0, signals(group, inputs), natures, statements);
    }

    /** Gewicht einer Einheit: Art mal Textmenge (gedämpft) mal Zahl der Fundstellen. */
    static double weight(ChangeUnit unit) {
        double nature = switch (unit.nature()) {
            case CORRECTION -> 0.2;
            case NUMBER_OR_DATE -> 0.5;
            case RELOCATION, STRUCTURE -> 0.3;
            case MAJOR_REVISION -> 1.5;
            default -> 1.0;
        };
        return nature * (1.0 + Math.log1p(unit.textSize())) * unit.occurrences().size();
    }

    /** Die dem Schwerpunkt nächsten Mitglieder; ohne Vektoren die größten. Gleichstände nach Reihenfolge. */
    private static List<String> representatives(List<ChangeUnit> members, Map<String, float[]> vectors) {
        List<float[]> present = new ArrayList<>();
        for (ChangeUnit unit : members) {
            if (vectors.containsKey(unit.id())) {
                present.add(vectors.get(unit.id()));
            }
        }
        List<ChangeUnit> ranked = new ArrayList<>(members);
        if (!present.isEmpty()) {
            float[] centroid = mean(present);
            ranked.sort(Comparator.comparingDouble((ChangeUnit unit) -> vectors.containsKey(unit.id())
                    ? -cosine(vectors.get(unit.id()), centroid) : Double.MAX_VALUE));
        } else {
            ranked.sort(Comparator.comparingInt((ChangeUnit unit) -> -unit.textSize()));
        }
        return ranked.stream().limit(REPRESENTATIVES).map(ChangeUnit::id).toList();
    }

    /** Häufigste Überschrift, sonst häufigster Begriff, sonst Titel der ersten Seite. Gleichstände alphabetisch. */
    static String label(List<ChangeUnit> members) {
        Map<String, Integer> headings = new TreeMap<>();
        Map<String, Integer> terms = new TreeMap<>();
        for (ChangeUnit unit : members) {
            for (Occurrence occurrence : unit.occurrences()) {
                if (!occurrence.headingPath().isEmpty()) {
                    headings.merge(occurrence.headingPath().getLast(), 1, Integer::sum);
                }
            }
            unit.terms().forEach(term -> terms.merge(term, 1, Integer::sum));
        }
        Optional<String> heading = mostFrequent(headings);
        if (heading.isPresent()) {
            return heading.get();
        }
        Optional<String> term = mostFrequent(terms);
        if (term.isPresent() && terms.get(term.get()) > 1) {
            return term.get();
        }
        String title = members.getFirst().first().title();
        return title != null ? title : term.orElse("");
    }

    private static Optional<String> mostFrequent(Map<String, Integer> counts) {
        return counts.entrySet().stream().max(Comparator.comparingInt((Map.Entry<String, Integer> entry)
                -> entry.getValue()).thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey);
    }

    /** Mittlere Ähnlichkeit je Signal zwischen den Bündeln des Themas (höchstens {@value #SIGNAL_PAIRS} Paare). */
    private Map<Signal, Double> signals(List<Bundle> group, Inputs inputs) {
        Map<Signal, double[]> sums = new EnumMap<>(Signal.class);
        int pairs = 0;
        outer:
        for (int a = 0; a < group.size(); a++) {
            for (int b = a + 1; b < group.size(); b++) {
                if (pairs++ >= SIGNAL_PAIRS) {
                    break outer;
                }
                similarities(group.get(a), group.get(b), inputs).forEach((signal, value) -> {
                    double[] sum = sums.computeIfAbsent(signal, ignored -> new double[2]);
                    sum[0] += value;
                    sum[1]++;
                });
            }
        }
        Map<Signal, Double> means = new EnumMap<>(Signal.class);
        sums.forEach((signal, sum) -> means.put(signal, sum[0] / sum[1]));
        return means;
    }

    private String inputSha256(List<ChangeUnit> units, Set<Signal> available, Inputs inputs) {
        StringBuilder input = new StringBuilder(METHOD).append('\n').append(weights.version()).append('\n')
                .append(available).append('\n').append(inputs.modelId()).append('\n');
        for (ChangeUnit unit : units) {
            input.append(unit.id()).append('\u0000').append(unit.nature()).append('\u0000')
                    .append(String.join("\u0001", unit.changedBefore())).append('\u0000')
                    .append(String.join("\u0001", unit.changedAfter())).append('\u0000')
                    .append(unit.occurrences().size()).append('\n');
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static float[] mean(List<float[]> vectors) {
        float[] mean = new float[vectors.getFirst().length];
        for (float[] vector : vectors) {
            for (int i = 0; i < mean.length && i < vector.length; i++) {
                mean[i] += vector[i] / vectors.size();
            }
        }
        return mean;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        int shared = 0;
        for (String value : a) {
            if (b.contains(value)) {
                shared++;
            }
        }
        int union = a.size() + b.size() - shared;
        return union == 0 ? 0 : (double) shared / union;
    }

    /** Einheiten einer Seite und eines Abschnitts mit ihren zusammengelegten Merkmalen. */
    static final class Bundle {

        private final List<ChangeUnit> units = new ArrayList<>();
        private final List<float[]> vectorsOfUnits = new ArrayList<>();
        private final Set<PageKey> pages = new LinkedHashSet<>();
        private final Set<String> parents = new LinkedHashSet<>();
        private final Set<List<String>> paths = new LinkedHashSet<>();
        private final Set<String> terms = new java.util.TreeSet<>();
        private Set<String> objects;
        private List<String> headingPath = List.of();
        private float[] vector;
        private double weight;

        void add(ChangeUnit unit, float[] unitVector, Set<String> unitObjects) {
            if (units.isEmpty()) {
                headingPath = unit.first().headingPath();
            }
            units.add(unit);
            if (unitVector != null) {
                vectorsOfUnits.add(unitVector);
            }
            for (Occurrence occurrence : unit.occurrences()) {
                pages.add(occurrence.page());
                if (occurrence.parentId() != null) {
                    parents.add(occurrence.parentId());
                }
                paths.add(occurrence.path());
            }
            terms.addAll(unit.terms());
            if (unitObjects != null) {
                if (objects == null) {
                    objects = new java.util.TreeSet<>();
                }
                objects.addAll(unitObjects);
            }
            weight += TopicProposer.weight(unit);
        }

        void finish() {
            vector = vectorsOfUnits.isEmpty() ? null : mean(vectorsOfUnits);
        }
    }
}
