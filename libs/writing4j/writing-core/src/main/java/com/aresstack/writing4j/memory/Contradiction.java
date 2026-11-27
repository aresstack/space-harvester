package com.aresstack.writing4j.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Record "Widerspruch" of the shared data model (component 17): two pieces of direct evidence on the same question or
 * topic, from different source groups, with the verdict whether they contradict each other and what became of it.
 * Nothing is resolved here: a contradiction stays open until the text shows both sides or the user decides it.
 * Immutable.
 * <p>
 * Field names of the data model: widerspruchId, frageId or themaId, two evidence verdicts (each by question and
 * passage), Urteil (WIDERSPRUCH, VEREINBAR, UNKLAR), Grund, Zustand (OFFEN, IM_TEXT_DARGESTELLT, and the user's
 * decision, named VON_ANGELO_ENTSCHIEDEN in the build plan). Added: what separates two statements that both hold
 * ({@link Difference}), the similarity that put the pair forward, the model, the version of the prompts and, for a
 * failed call, FEHLER with its cause. A failed call is never a VEREINBAR; the pair is offered again.
 * <p>
 * The id comes from the two texts (their keys, in either order), so the same two statements get the same id under
 * whichever question they meet. A later record with the same id (a new verdict, a new state) replaces the earlier
 * one when the memory is read.
 */
public final class Contradiction {

    /** Where the two pieces of evidence meet. */
    public enum Scope {
        /** Both answer the same question. */
        FRAGE,
        /** They answer different questions of the same topic. */
        THEMA
    }

    /** The model's verdict. */
    public enum Verdict {
        /** Both cannot hold at once for the same case, law, time and group. */
        WIDERSPRUCH,
        /** Both can hold at once. */
        VEREINBAR,
        /** The two texts do not tell. */
        UNKLAR
    }

    /** What separates two statements that both hold; {@link #KEINER} for a contradiction or an unclear pair. */
    public enum Difference {
        /** Another country's law or authority. */
        RECHTSRAUM,
        /** Another year or state of the rules. */
        JAHR,
        /** Another group of people, devices or firms. */
        GRUPPE,
        /** Another situation or purpose. */
        ZUSAMMENHANG,
        /** They speak of different things. */
        SACHE,
        /** Nothing: they agree, contradict each other or cannot be told apart. */
        KEINER
    }

    /** Whether the verdict holds or the pair must be offered again. */
    public enum Status {
        OK, FEHLER
    }

    /** What became of the pair. */
    public enum State {
        /** Not shown in the text yet, not decided. */
        OFFEN,
        /** The text presents both sides with both sources. */
        IM_TEXT_DARGESTELLT,
        /** The user decided it (build plan: VON_ANGELO_ENTSCHIEDEN). */
        VOM_NUTZER_ENTSCHIEDEN
    }

    /** One of the two pieces of evidence: the verdict it rests on, named by question and passage. */
    public static final class Side {
        private final String questionId;
        private final String passageId;
        private final String sourceId;
        private final String sourceUrl;
        private final String textKey;

        public Side(String questionId, String passageId, String sourceId, String sourceUrl, String textKey) {
            this.questionId = Texts.clean(questionId);
            this.passageId = Texts.clean(passageId);
            this.sourceId = Texts.clean(sourceId);
            this.sourceUrl = Texts.clean(sourceUrl);
            String key = Texts.clean(textKey);
            this.textKey = key.isEmpty() ? "passage:" + this.passageId : key;
            if (this.questionId.isEmpty() || this.passageId.isEmpty()) {
                throw new IllegalArgumentException("a side of a contradiction needs a question id and a passage id");
            }
        }

        /** The side a verdict makes. */
        public static Side of(EvidenceVerdict verdict) {
            return new Side(verdict.getQuestionId(), verdict.getPassageId(), verdict.getSourceId(),
                    verdict.getSourceUrl(), verdict.getTextKey());
        }

        public String getQuestionId() {
            return questionId;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public String getTextKey() {
            return textKey;
        }
    }

    private final String id;
    private final Scope scope;
    private final String scopeId;
    private final Side a;
    private final Side b;
    private final double similarity;
    private final Status status;
    private final Verdict verdict;
    private final Difference difference;
    private final String reason;
    private final String problem;
    private final State state;
    private final String decision;
    private final String model;
    private final int version;
    private final long timeMillis;

    Contradiction(String id, Scope scope, String scopeId, Side a, Side b, double similarity, Status status,
                  Verdict verdict, Difference difference, String reason, String problem, State state, String decision,
                  String model, int version, long timeMillis) {
        if (scope == null || Texts.clean(scopeId).isEmpty() || a == null || b == null) {
            throw new IllegalArgumentException("a contradiction needs a question or topic and two sides");
        }
        if (a.getTextKey().equals(b.getTextKey())) {
            throw new IllegalArgumentException("the two sides of a contradiction are the same text");
        }
        this.scope = scope;
        this.scopeId = Texts.clean(scopeId);
        this.a = a;
        this.b = b;
        String own = idOf(a, b);
        String given = Texts.clean(id);
        if (!given.isEmpty() && !given.equals(own)) {
            throw new IllegalArgumentException("contradiction " + given + " does not belong to its two texts (" + own
                    + ")");
        }
        this.id = own;
        this.similarity = similarity;
        this.status = status == null ? Status.FEHLER : status;
        this.verdict = this.status == Status.OK ? verdict : null;
        if (this.status == Status.OK && verdict == null) {
            throw new IllegalArgumentException("a contradiction without FEHLER needs a verdict");
        }
        this.difference = this.verdict == Verdict.VEREINBAR && difference != null ? difference : Difference.KEINER;
        this.reason = Texts.clean(reason);
        this.problem = this.status == Status.FEHLER ? Texts.clean(problem) : "";
        this.state = state == null ? State.OFFEN : state;
        this.decision = Texts.clean(decision);
        this.model = Texts.clean(model);
        this.version = Math.max(1, version);
        this.timeMillis = timeMillis;
    }

    /** A verdict of the model. */
    public static Contradiction judged(Scope scope, String scopeId, Side a, Side b, double similarity, Verdict verdict,
                                       Difference difference, String reason, String model, int version,
                                       long timeMillis) {
        return new Contradiction("", scope, scopeId, a, b, similarity, Status.OK, verdict, difference, reason, "",
                State.OFFEN, "", model, version, timeMillis);
    }

    /** A call that gave no verdict; the pair is offered again. */
    public static Contradiction failed(Scope scope, String scopeId, Side a, Side b, double similarity, String problem,
                                       String model, int version, long timeMillis) {
        return new Contradiction("", scope, scopeId, a, b, similarity, Status.FEHLER, null, null, "", problem,
                State.OFFEN, "", model, version, timeMillis);
    }

    /**
     * The same verdict in a new state: the text shows both sides, or the user decided (with what they decided).
     *
     * @throws IllegalStateException for a pair without a verdict
     */
    public Contradiction withState(State next, String decisionText, long time) {
        if (status != Status.OK) {
            throw new IllegalStateException("contradiction " + id + " has no verdict yet");
        }
        return new Contradiction(id, scope, scopeId, a, b, similarity, status, verdict, difference, reason, problem,
                next, decisionText, model, version, time);
    }

    /** {@code widerspruch-} and 16 hex digits of SHA-256 over the two text keys in sorted order. */
    public static String idOf(Side a, Side b) {
        String first = a.getTextKey();
        String second = b.getTextKey();
        if (first.compareTo(second) > 0) {
            String swap = first;
            first = second;
            second = swap;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((first + "\u0000" + second)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("widerspruch-");
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A verdict WIDERSPRUCH that the text does not show yet and the user did not decide. */
    public boolean isOpen() {
        return status == Status.OK && verdict == Verdict.WIDERSPRUCH && state == State.OFFEN;
    }

    public String getId() {
        return id;
    }

    public Scope getScope() {
        return scope;
    }

    /** The question id (scope FRAGE) or the topic id (scope THEMA). */
    public String getScopeId() {
        return scopeId;
    }

    public Side getA() {
        return a;
    }

    public Side getB() {
        return b;
    }

    /** Cosine similarity of the two quotes that put the pair forward. */
    public double getSimilarity() {
        return similarity;
    }

    public Status getStatus() {
        return status;
    }

    /** {@code null} for FEHLER. */
    public Verdict getVerdict() {
        return verdict;
    }

    public Difference getDifference() {
        return difference;
    }

    public String getReason() {
        return reason;
    }

    /** Why the call gave no verdict; {@code ""} unless FEHLER. */
    public String getProblem() {
        return problem;
    }

    public State getState() {
        return state;
    }

    /** What the user decided; {@code ""} unless they did. */
    public String getDecision() {
        return decision;
    }

    public String getModel() {
        return model;
    }

    /** The version of the prompts that gave the verdict; a verdict is reused only under the same version. */
    public int getVersion() {
        return version;
    }

    public long getTimeMillis() {
        return timeMillis;
    }
}
