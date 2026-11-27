package com.aresstack.writing4j.planwriter;

import com.aresstack.writing4j.missionreport.BookSection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One section as the writer (component 10) left it: the text with its markers {@code [n]} and what each marker cites,
 * the sentences that stayed marked after the sentence check, the gaps it reports to the research lead instead of
 * searching itself, and counts. A failed section carries the cause and no text. Immutable; {@link #toJson()} and
 * {@link #fromJson(JsonObject)} carry it whole, {@link #toBookSection()} gives the mission report (component 18) its
 * view.
 */
public final class WrittenSection {

    /** How the section came out. */
    public enum Status {
        /** Written; marked sentences may remain. */
        OK,
        /** The writer failed (model call); no text. */
        FEHLER,
        /** No passage of the section had a text: nothing to write from. */
        OHNE_STOFF
    }

    /** What a cited marker stands for: the passage as the writer read it. */
    public static final class Citation {
        private final int marker;
        private final String passageId;
        private final String sourceId;
        private final String passageText;

        public Citation(int marker, String passageId, String sourceId, String passageText) {
            this.marker = marker;
            this.passageId = clean(passageId);
            this.sourceId = clean(sourceId);
            this.passageText = passageText == null ? "" : passageText;
        }

        public int getMarker() {
            return marker;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getPassageText() {
            return passageText;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("marke", marker);
            json.addProperty("passageId", passageId);
            json.addProperty("quelleId", sourceId);
            json.addProperty("text", passageText);
            return json;
        }

        static Citation fromJson(JsonObject json) {
            return new Citation((int) number(json, "marke"), string(json, "passageId"), string(json, "quelleId"),
                    string(json, "text"));
        }
    }

    /** A gap the writer reports to the research lead (component 8); it never searches itself. */
    public static final class Gap {
        /** What is missing. */
        public enum Kind {
            /** A question of the section without evidence ("Kein Beleg gefunden"): the section says nothing on it. */
            FRAGE_OHNE_BELEG,
            /** A question of the section with leads only ("Weiterführender Hinweis"): no source answers it directly. */
            NUR_HINWEISE,
            /** A point the writer named after the text ({@code GAP:} line): the passages do not cover it. */
            OFFENER_PUNKT,
            /** The section stayed well below the words the plan gave it. */
            UNTER_ZIEL
        }

        private final Kind kind;
        private final String questionId;
        private final String text;

        public Gap(Kind kind, String questionId, String text) {
            if (kind == null) {
                throw new IllegalArgumentException("a gap needs a kind");
            }
            this.kind = kind;
            this.questionId = clean(questionId);
            this.text = clean(text);
        }

        public Kind getKind() {
            return kind;
        }

        /** The question of the section the gap is about; {@code ""} for an open point or the length. */
        public String getQuestionId() {
            return questionId;
        }

        public String getText() {
            return text;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("art", kind.name());
            json.addProperty("frageId", questionId);
            json.addProperty("text", text);
            return json;
        }

        static Gap fromJson(JsonObject json) {
            return new Gap(Kind.valueOf(string(json, "art")), string(json, "frageId"), string(json, "text"));
        }
    }

    /** A sentence that stayed in the text although the sentence check (component 11) did not let it pass. */
    public static final class Mark {
        /** Why it is marked. */
        public enum Kind {
            /** No cited passage carries it. */
            NICHT_GEDECKT,
            /** It states a claim of a maker, a seller or an association as a fact. */
            ALS_TATSACHE,
            /** It names a rule of another legal space without saying where it holds. */
            RECHTSRAUM_OFFEN,
            /** It cites no passage at all. */
            OHNE_BELEG,
            /** It names a source by the letter the writer's prompt gave it ("Quelle C"), which no reader knows. */
            QUELLE_BUCHSTABE
        }

        private final Kind kind;
        private final String sentence;
        private final String verdict;
        private final String reason;
        private final boolean rewritten;

        /**
         * @param verdict   the sentence's verdict (GEDECKT, TEILWEISE, NICHT_GEDECKT), {@code ""} when there is none
         * @param reason    why: the check's reason, or what became of the attempt to write it again
         * @param rewritten whether the text holds the sentence as the writer wrote it again
         */
        public Mark(Kind kind, String sentence, String verdict, String reason, boolean rewritten) {
            if (kind == null) {
                throw new IllegalArgumentException("a mark needs a kind");
            }
            this.kind = kind;
            this.sentence = sentence == null ? "" : sentence.trim();
            this.verdict = clean(verdict);
            this.reason = clean(reason);
            this.rewritten = rewritten;
        }

        public Kind getKind() {
            return kind;
        }

        /** The sentence as the text holds it, markers included. */
        public String getSentence() {
            return sentence;
        }

        public String getVerdict() {
            return verdict;
        }

        public String getReason() {
            return reason;
        }

        public boolean isRewritten() {
            return rewritten;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("art", kind.name());
            json.addProperty("satz", sentence);
            json.addProperty("urteil", verdict);
            json.addProperty("grund", reason);
            json.addProperty("ueberarbeitet", rewritten);
            return json;
        }

        static Mark fromJson(JsonObject json) {
            JsonElement rewritten = json.get("ueberarbeitet");
            return new Mark(Kind.valueOf(string(json, "art")), string(json, "satz"), string(json, "urteil"),
                    string(json, "grund"), rewritten != null && rewritten.getAsBoolean());
        }
    }

    private final String sectionId;
    private final String title;
    private final String chapterTitle;
    private final int targetWords;
    private final Status status;
    private final String failure;
    private final String text;
    private final int words;
    private final Map<Integer, Citation> citations;
    private final List<Gap> gaps;
    private final List<Mark> marks;
    private final List<String> withoutText;
    private final Map<String, Integer> counts;
    private final String model;
    private final String promptHash;
    private final int version;
    private final long timeMillis;
    private final long millis;

    /**
     * @param counts      sentences, verdicts, rewrites and calls by name ({@link #getCounts()})
     * @param withoutText the passage ids of the section the stock has no text for
     */
    public WrittenSection(String sectionId, String title, String chapterTitle, int targetWords, Status status,
                          String failure, String text, int words, List<Citation> citations, List<Gap> gaps,
                          List<Mark> marks, List<String> withoutText, Map<String, Integer> counts, String model,
                          String promptHash, int version, long timeMillis, long millis) {
        this.sectionId = clean(sectionId);
        if (this.sectionId.isEmpty()) {
            throw new IllegalArgumentException("a written section needs an id");
        }
        this.title = clean(title);
        this.chapterTitle = clean(chapterTitle);
        this.targetWords = Math.max(0, targetWords);
        this.status = status == null ? Status.FEHLER : status;
        this.failure = clean(failure);
        this.text = text == null ? "" : text;
        this.words = Math.max(0, words);
        Map<Integer, Citation> byMarker = new LinkedHashMap<Integer, Citation>();
        for (Citation citation : citations == null ? Collections.<Citation>emptyList() : citations) {
            byMarker.putIfAbsent(citation.getMarker(), citation);
        }
        this.citations = Collections.unmodifiableMap(byMarker);
        this.gaps = Collections.unmodifiableList(new ArrayList<Gap>(gaps == null ? Collections.<Gap>emptyList()
                : gaps));
        this.marks = Collections.unmodifiableList(new ArrayList<Mark>(marks == null ? Collections.<Mark>emptyList()
                : marks));
        this.withoutText = Collections.unmodifiableList(new ArrayList<String>(withoutText == null
                ? Collections.<String>emptyList() : withoutText));
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(counts == null
                ? Collections.<String, Integer>emptyMap() : counts));
        this.model = clean(model);
        this.promptHash = clean(promptHash);
        this.version = version;
        this.timeMillis = timeMillis;
        this.millis = millis;
    }

    public String getSectionId() {
        return sectionId;
    }

    public String getTitle() {
        return title;
    }

    public String getChapterTitle() {
        return chapterTitle;
    }

    /** The words the section plan gave the section. */
    public int getTargetWords() {
        return targetWords;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public String getFailure() {
        return failure;
    }

    /** The text, paragraphs parted by blank lines, every marker {@code [n]} one of {@link #getCitations()}. */
    public String getText() {
        return text;
    }

    public int getWords() {
        return words;
    }

    public Map<Integer, Citation> getCitations() {
        return citations;
    }

    public List<Gap> getGaps() {
        return gaps;
    }

    public List<Mark> getMarks() {
        return marks;
    }

    public List<String> getWithoutText() {
        return withoutText;
    }

    /**
     * Counts by name: {@code saetze}, {@code mitBeleg}, {@code gedeckt}, {@code teilweise}, {@code nichtGedeckt},
     * {@code ungeprueft}, {@code alsTatsache}, {@code rechtsraumOffen}, {@code zurueck}, {@code ueberarbeitet},
     * {@code markiert}, {@code ohneBeleg}, {@code schreibAufrufe}, {@code neuAufrufe}, {@code pruefPaare},
     * {@code pruefGefragt}, {@code passagen}, {@code belegt}, {@code hinweise}, {@code kernpunkte}.
     */
    public Map<String, Integer> getCounts() {
        return counts;
    }

    public int count(String name) {
        Integer value = counts.get(name);
        return value == null ? 0 : value;
    }

    public String getModel() {
        return model;
    }

    public String getPromptHash() {
        return promptHash;
    }

    /** The writer's {@link PlanSectionWriter#VERSION} the section was written under. */
    public int getVersion() {
        return version;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    /** How long writing, checking and writing again took. */
    public long getMillis() {
        return millis;
    }

    /** The section as the mission report (component 18) reads it. */
    public BookSection toBookSection() {
        List<BookSection.Citation> cited = new ArrayList<BookSection.Citation>();
        for (Citation citation : citations.values()) {
            if (citation.getMarker() >= 1) {
                cited.add(new BookSection.Citation(citation.getMarker(), citation.getPassageId(),
                        citation.getSourceId(), citation.getPassageText()));
            }
        }
        return new BookSection(sectionId, title, targetWords, text, cited);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("abschnittId", sectionId);
        json.addProperty("titel", title);
        json.addProperty("kapitel", chapterTitle);
        json.addProperty("zielwoerter", targetWords);
        json.addProperty("status", status.name());
        json.addProperty("fehler", failure);
        json.addProperty("woerter", words);
        json.addProperty("text", text);
        JsonArray cited = new JsonArray();
        for (Citation citation : citations.values()) {
            cited.add(citation.toJson());
        }
        json.add("zitate", cited);
        JsonArray open = new JsonArray();
        for (Gap gap : gaps) {
            open.add(gap.toJson());
        }
        json.add("luecken", open);
        JsonArray marked = new JsonArray();
        for (Mark mark : marks) {
            marked.add(mark.toJson());
        }
        json.add("markiert", marked);
        JsonArray missing = new JsonArray();
        for (String id : withoutText) {
            missing.add(id);
        }
        json.add("ohneText", missing);
        JsonObject numbers = new JsonObject();
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            numbers.addProperty(count.getKey(), count.getValue());
        }
        json.add("zaehler", numbers);
        json.addProperty("modell", model);
        json.addProperty("promptHash", promptHash);
        json.addProperty("fassung", version);
        json.addProperty("zeit", timeMillis);
        json.addProperty("ms", millis);
        return json;
    }

    public static WrittenSection fromJson(JsonObject json) {
        List<Citation> cited = new ArrayList<Citation>();
        for (JsonObject c : objects(json, "zitate")) {
            cited.add(Citation.fromJson(c));
        }
        List<Gap> open = new ArrayList<Gap>();
        for (JsonObject g : objects(json, "luecken")) {
            open.add(Gap.fromJson(g));
        }
        List<Mark> marked = new ArrayList<Mark>();
        for (JsonObject m : objects(json, "markiert")) {
            marked.add(Mark.fromJson(m));
        }
        List<String> missing = new ArrayList<String>();
        JsonElement ids = json.get("ohneText");
        if (ids != null && ids.isJsonArray()) {
            for (JsonElement id : ids.getAsJsonArray()) {
                missing.add(id.getAsString());
            }
        }
        Map<String, Integer> numbers = new LinkedHashMap<String, Integer>();
        JsonElement counted = json.get("zaehler");
        if (counted != null && counted.isJsonObject()) {
            for (Map.Entry<String, JsonElement> count : counted.getAsJsonObject().entrySet()) {
                numbers.put(count.getKey(), count.getValue().getAsInt());
            }
        }
        return new WrittenSection(string(json, "abschnittId"), string(json, "titel"), string(json, "kapitel"),
                (int) number(json, "zielwoerter"), Status.valueOf(string(json, "status")), string(json, "fehler"),
                string(json, "text"), (int) number(json, "woerter"), cited, open, marked, missing, numbers,
                string(json, "modell"), string(json, "promptHash"), (int) number(json, "fassung"),
                number(json, "zeit"), number(json, "ms"));
    }

    @Override
    public String toString() {
        return sectionId + " " + status + (failure.isEmpty() ? "" : " (" + failure + ")") + ", " + words + " of "
                + targetWords + " words, " + marks.size() + " marked, " + gaps.size() + " gaps";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? 0L : value.getAsLong();
    }

    private static List<JsonObject> objects(JsonObject json, String key) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        JsonElement value = json.get(key);
        if (value != null && value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                out.add(element.getAsJsonObject());
            }
        }
        return out;
    }
}
