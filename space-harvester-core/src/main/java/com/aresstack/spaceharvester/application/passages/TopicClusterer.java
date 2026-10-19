package com.aresstack.spaceharvester.application.passages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Hierarchisches agglomeratives Clustering über Absatzvektoren mit Cosinus-Distanz und Average Linkage, kopiert aus
 * dem Enterprise AI Client ({@code astu/acropolis/…/passage/TopicClusterer}, dort aus askai-java8 {@code arch}): keine
 * feste Themenzahl, kleine Einzelthemen bleiben sichtbar. Deterministisch: Gleichstände entscheidet die
 * Eingabereihenfolge. Titel und Zusammenfassung stammen aus dem Mitglied, das dem Schwerpunkt am nächsten ist; die
 * Mitgliedschaft hängt nie von dieser Formulierung ab (#181, ADR 0020).
 */
public final class TopicClusterer {

    /** Wie im Client: verschmelzen bis zur Cosinus-Distanz 0,55. */
    public static final double DEFAULT_MERGE_DISTANCE_CUTOFF = 0.55;
    /** Wie im Client: zwei Repräsentanten je Thema. */
    public static final int DEFAULT_REPRESENTATIVES = 2;

    private final double mergeDistanceCutoff;
    private final int representativesPerTopic;

    public TopicClusterer() {
        this(DEFAULT_MERGE_DISTANCE_CUTOFF, DEFAULT_REPRESENTATIVES);
    }

    public TopicClusterer(double mergeDistanceCutoff, int representativesPerTopic) {
        this.mergeDistanceCutoff = mergeDistanceCutoff;
        this.representativesPerTopic = representativesPerTopic;
    }

    /** Ein zu gruppierender Absatz. */
    public record Member(String id, String text, float[] vector) {

        public Member {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(vector, "vector");
        }
    }

    /**
     * Ein gefundenes Thema.
     *
     * @param topicId           ID des ersten Mitglieds in Eingabereihenfolge
     * @param memberIds         die Mitglieder in Verschmelzungsreihenfolge
     * @param representativeIds die dem Schwerpunkt nächsten Mitglieder, das nächste zuerst
     * @param suggestedTitle    der erste Satz des Repräsentanten, höchstens 80 Zeichen
     * @param confidence        mittlere Ähnlichkeit der Mitglieder zum Schwerpunkt
     */
    public record Topic(String topicId, List<String> memberIds, List<String> representativeIds,
                        String suggestedTitle, String suggestedSummary, double confidence) {

        public Topic {
            memberIds = List.copyOf(memberIds);
            representativeIds = List.copyOf(representativeIds);
        }
    }

    public List<Topic> cluster(List<Member> members) {
        List<List<Member>> clusters = new ArrayList<>();
        for (Member member : members) {
            clusters.add(new ArrayList<>(List.of(member)));
        }
        // Agglomerieren: immer das gerade nächste Paar verschmelzen, bis die Grenzdistanz erreicht ist.
        while (clusters.size() > 1) {
            int bestA = -1;
            int bestB = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int a = 0; a < clusters.size(); a++) {
                for (int b = a + 1; b < clusters.size(); b++) {
                    double distance = averageLinkageDistance(clusters.get(a), clusters.get(b));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            if (bestDistance > mergeDistanceCutoff) {
                break;
            }
            clusters.get(bestA).addAll(clusters.remove(bestB));
        }
        List<Topic> topics = new ArrayList<>();
        for (List<Member> cluster : clusters) {
            topics.add(toTopic(cluster, members));
        }
        return topics;
    }

    private static double averageLinkageDistance(List<Member> a, List<Member> b) {
        double sum = 0;
        int pairs = 0;
        for (Member pa : a) {
            for (Member pb : b) {
                sum += 1.0 - VectorMath.cosine(VectorMath.values(pa.vector()), VectorMath.values(pb.vector()));
                pairs++;
            }
        }
        return sum / pairs;
    }

    private Topic toTopic(List<Member> cluster, List<Member> inputOrder) {
        List<float[]> vectors = new ArrayList<>();
        List<String> memberIds = new ArrayList<>();
        for (Member member : cluster) {
            memberIds.add(member.id());
            vectors.add(member.vector());
        }
        double[] centroid = VectorMath.meanValues(vectors);
        // Repräsentanten: die dem Schwerpunkt nächsten Mitglieder, Gleichstände nach Einfügereihenfolge.
        List<Member> byCentroid = new ArrayList<>(cluster);
        byCentroid.sort(Comparator.comparingDouble(
                member -> 1.0 - VectorMath.cosine(VectorMath.values(member.vector()), centroid)));
        List<String> representatives = new ArrayList<>();
        for (int i = 0; i < Math.min(representativesPerTopic, byCentroid.size()); i++) {
            representatives.add(byCentroid.get(i).id());
        }
        double intra = 0;
        for (float[] vector : vectors) {
            intra += VectorMath.cosine(VectorMath.values(vector), centroid);
        }
        return new Topic(firstInInputOrder(cluster, inputOrder), memberIds, representatives,
                suggestedTitle(byCentroid.getFirst()), byCentroid.getFirst().text(), intra / vectors.size());
    }

    private static String firstInInputOrder(List<Member> cluster, List<Member> inputOrder) {
        for (Member member : inputOrder) {
            if (cluster.contains(member)) {
                return member.id();
            }
        }
        return cluster.getFirst().id();
    }

    /** Deterministischer, belegnaher Titel: der erste Satz des Repräsentanten, gekürzt. */
    static String suggestedTitle(Member representative) {
        String text = representative.text();
        int end = text.indexOf('.');
        String head = end > 0 ? text.substring(0, end) : text;
        return head.length() <= 80 ? head : head.substring(0, 80);
    }
}
