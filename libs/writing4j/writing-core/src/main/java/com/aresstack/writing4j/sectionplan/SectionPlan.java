package com.aresstack.writing4j.sectionplan;

import com.aresstack.writing4j.missionreport.Placement;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The section plan of the book (component 9): chapters and sections with their words, the evidence each section may
 * cite, and the fate of every topic of the research memory (own section, gone into a section, or left out with a
 * reason). The writer (component 10) writes a section only from the evidence the plan gives it; the mission report
 * (component 18) lists the fates. Immutable; {@link #toJson()} and {@link #fromJson(JsonObject)} carry it whole.
 */
public final class SectionPlan {

    /** Where a chapter comes from. */
    public enum ChapterKind {
        /** A topic found in the material, with its subtopics. */
        GEFUNDEN,
        /** Found topics without a chapter of their own and without a fitting section elsewhere. */
        SAMMEL
    }

    /** A passage a section may cite, with the questions it answers (or leads further on) and their topics. */
    public static final class Evidence {
        private final String passageId;
        private final String sourceId;
        private final String sourceUrl;
        private final String textKey;
        private final List<String> questionIds;
        private final List<String> topicIds;

        public Evidence(String passageId, String sourceId, String sourceUrl, String textKey, List<String> questionIds,
                        List<String> topicIds) {
            this.passageId = clean(passageId);
            this.sourceId = clean(sourceId);
            this.sourceUrl = clean(sourceUrl);
            this.textKey = clean(textKey);
            this.questionIds = ids(questionIds);
            this.topicIds = ids(topicIds);
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

        /** Two entries with the same key are the same text ({@code PassageText.key}). */
        public String getTextKey() {
            return textKey;
        }

        public List<String> getQuestionIds() {
            return questionIds;
        }

        public List<String> getTopicIds() {
            return topicIds;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("passageId", passageId);
            json.addProperty("quelleId", sourceId);
            json.addProperty("quelleUrl", sourceUrl);
            json.addProperty("textKey", textKey);
            json.add("fragen", array(questionIds));
            json.add("themen", array(topicIds));
            return json;
        }

        static Evidence fromJson(JsonObject json) {
            return new Evidence(string(json, "passageId"), string(json, "quelleId"), string(json, "quelleUrl"),
                    string(json, "textKey"), strings(json, "fragen"), strings(json, "themen"));
        }
    }

    /** A question of a topic in the section, with what the memory holds for it in the words of the app. */
    public static final class Question {
        private final String id;
        private final String text;
        private final String topicId;
        private final String standing;

        /**
         * @param standing "Belegt", "Weiterführender Hinweis" or "Kein Beleg gefunden" (the labels of
         *                 {@code QuestionEvidence}); the writer writes no statement for a question without evidence
         */
        public Question(String id, String text, String topicId, String standing) {
            this.id = clean(id);
            this.text = clean(text);
            this.topicId = clean(topicId);
            this.standing = clean(standing);
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public String getTopicId() {
            return topicId;
        }

        public String getStanding() {
            return standing;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("text", text);
            json.addProperty("themaId", topicId);
            json.addProperty("beleglage", standing);
            return json;
        }

        static Question fromJson(JsonObject json) {
            return new Question(string(json, "id"), string(json, "text"), string(json, "themaId"),
                    string(json, "beleglage"));
        }
    }

    /** An open contradiction (component 17) the section has to present with both sources. */
    public static final class Contradiction {
        private final String id;
        private final String scope;
        private final String scopeId;
        private final String sourceA;
        private final String sourceB;
        private final String verdict;

        public Contradiction(String id, String scope, String scopeId, String sourceA, String sourceB, String verdict) {
            this.id = clean(id);
            this.scope = clean(scope);
            this.scopeId = clean(scopeId);
            this.sourceA = clean(sourceA);
            this.sourceB = clean(sourceB);
            this.verdict = clean(verdict);
        }

        public String getId() {
            return id;
        }

        /** {@code FRAGE} or {@code THEMA}. */
        public String getScope() {
            return scope;
        }

        public String getScopeId() {
            return scopeId;
        }

        public String getSourceA() {
            return sourceA;
        }

        public String getSourceB() {
            return sourceB;
        }

        /** {@code WIDERSPRUCH} or {@code UNKLAR}. */
        public String getVerdict() {
            return verdict;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("umfang", scope);
            json.addProperty("umfangId", scopeId);
            json.addProperty("quelleA", sourceA);
            json.addProperty("quelleB", sourceB);
            json.addProperty("urteil", verdict);
            return json;
        }

        static Contradiction fromJson(JsonObject json) {
            return new Contradiction(string(json, "id"), string(json, "umfang"), string(json, "umfangId"),
                    string(json, "quelleA"), string(json, "quelleB"), string(json, "urteil"));
        }
    }

    /** A section: the topic it is named after, the topics gone into it, its words and what it may cite. */
    public static final class Section {
        private final String id;
        private final String title;
        private final String topicId;
        private final List<String> topicIds;
        private final long weight;
        private final int words;
        private final List<Evidence> evidence;
        private final List<Evidence> leads;
        private final List<Question> questions;
        private final List<Contradiction> contradictions;

        public Section(String id, String title, String topicId, List<String> topicIds, long weight, int words,
                       List<Evidence> evidence, List<Evidence> leads, List<Question> questions,
                       List<Contradiction> contradictions) {
            this.id = clean(id);
            this.title = clean(title);
            this.topicId = clean(topicId);
            this.topicIds = ids(topicIds);
            this.weight = weight;
            this.words = words;
            this.evidence = copy(evidence);
            this.leads = copy(leads);
            this.questions = copy(questions);
            this.contradictions = copy(contradictions);
            if (this.id.isEmpty()) {
                throw new IllegalArgumentException("a section needs an id");
            }
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        /** The topic with the section of its own. */
        public String getTopicId() {
            return topicId;
        }

        /** Its own topic first, then the topics gone into it. */
        public List<String> getTopicIds() {
            return topicIds;
        }

        /** The weight of its topics together: direct evidence and independent sources. */
        public long getWeight() {
            return weight;
        }

        public int getWords() {
            return words;
        }

        /** The direct evidence ("Belegt") the section may cite, one entry per text. */
        public List<Evidence> getEvidence() {
            return evidence;
        }

        /** Leads ("Weiterführender Hinweis"), one entry per text that is no direct evidence of the section. */
        public List<Evidence> getLeads() {
            return leads;
        }

        public List<Question> getQuestions() {
            return questions;
        }

        public List<Contradiction> getContradictions() {
            return contradictions;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("abschnittId", id);
            json.addProperty("titel", title);
            json.addProperty("themaId", topicId);
            json.add("themaIds", array(topicIds));
            json.addProperty("gewicht", weight);
            json.addProperty("zielwoerter", words);
            JsonArray direct = new JsonArray();
            for (Evidence e : evidence) {
                direct.add(e.toJson());
            }
            json.add("belege", direct);
            JsonArray further = new JsonArray();
            for (Evidence e : leads) {
                further.add(e.toJson());
            }
            json.add("hinweise", further);
            JsonArray asked = new JsonArray();
            for (Question q : questions) {
                asked.add(q.toJson());
            }
            json.add("fragen", asked);
            JsonArray open = new JsonArray();
            for (Contradiction c : contradictions) {
                open.add(c.toJson());
            }
            json.add("widersprueche", open);
            return json;
        }

        static Section fromJson(JsonObject json) {
            List<Evidence> direct = new ArrayList<Evidence>();
            for (JsonObject e : objects(json, "belege")) {
                direct.add(Evidence.fromJson(e));
            }
            List<Evidence> further = new ArrayList<Evidence>();
            for (JsonObject e : objects(json, "hinweise")) {
                further.add(Evidence.fromJson(e));
            }
            List<Question> asked = new ArrayList<Question>();
            for (JsonObject q : objects(json, "fragen")) {
                asked.add(Question.fromJson(q));
            }
            List<Contradiction> open = new ArrayList<Contradiction>();
            for (JsonObject c : objects(json, "widersprueche")) {
                open.add(Contradiction.fromJson(c));
            }
            return new Section(string(json, "abschnittId"), string(json, "titel"), string(json, "themaId"),
                    strings(json, "themaIds"), number(json, "gewicht"), (int) number(json, "zielwoerter"), direct,
                    further, asked, open);
        }
    }

    /** A chapter: a topic found in the material, or the collection of topics without a place. */
    public static final class Chapter {
        private final String id;
        private final String title;
        private final ChapterKind kind;
        private final String rootTopicId;
        private final double share;
        private final int words;
        private final List<Section> sections;

        /** @param share a share of the book fixed for the chapter, {@code NaN} when none is (the words then go by weight) */
        public Chapter(String id, String title, ChapterKind kind, String rootTopicId, double share, int words,
                       List<Section> sections) {
            this.id = clean(id);
            this.title = clean(title);
            this.kind = kind == null ? ChapterKind.GEFUNDEN : kind;
            this.rootTopicId = clean(rootTopicId);
            this.share = share;
            this.words = words;
            this.sections = copy(sections);
            if (this.id.isEmpty()) {
                throw new IllegalArgumentException("a chapter needs an id");
            }
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public ChapterKind getKind() {
            return kind;
        }

        /** The topic the chapter is named after; empty for the collection. */
        public String getRootTopicId() {
            return rootTopicId;
        }

        /** A share of the book fixed for the chapter, {@code NaN} when none is. */
        public double getShare() {
            return share;
        }

        /** The words the chapter's share or weight gives it. */
        public int getWords() {
            return words;
        }

        /** The words of its sections: its words, less what its material does not hold. */
        public int getPlannedWords() {
            int sum = 0;
            for (Section section : sections) {
                sum += section.getWords();
            }
            return sum;
        }

        /** The words its material does not hold ({@link SectionPlanner#wordsPerPassage}); 0 when it holds them all. */
        public int getShortfall() {
            return Math.max(0, words - getPlannedWords());
        }

        public List<Section> getSections() {
            return sections;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kapitelId", id);
            json.addProperty("titel", title);
            json.addProperty("art", kind.name());
            json.addProperty("wurzelThemaId", rootTopicId);
            if (!Double.isNaN(share)) {
                json.addProperty("anteil", share);
            }
            json.addProperty("woerter", words);
            json.addProperty("geplanteWoerter", getPlannedWords());
            json.addProperty("fehlendeWoerter", getShortfall());
            JsonArray list = new JsonArray();
            for (Section section : sections) {
                list.add(section.toJson());
            }
            json.add("abschnitte", list);
            return json;
        }

        static Chapter fromJson(JsonObject json) {
            List<Section> list = new ArrayList<Section>();
            for (JsonObject s : objects(json, "abschnitte")) {
                list.add(Section.fromJson(s));
            }
            ChapterKind kind;
            try {
                kind = ChapterKind.valueOf(string(json, "art"));
            } catch (IllegalArgumentException unknown) {
                kind = ChapterKind.GEFUNDEN;
            }
            double share = json.has("anteil") && !json.get("anteil").isJsonNull() ? json.get("anteil").getAsDouble()
                    : Double.NaN;
            return new Chapter(string(json, "kapitelId"), string(json, "titel"), kind, string(json, "wurzelThemaId"),
                    share, (int) number(json, "woerter"), list);
        }
    }

    /** One topic of the memory with its weight and its fate in the plan. */
    public static final class TopicLine {
        private final String topicId;
        private final String name;
        private final String fence;
        private final long weight;
        private final int direct;
        private final int sources;
        private final Placement placement;

        public TopicLine(String topicId, String name, String fence, long weight, int direct,
                         int sources, Placement placement) {
            this.topicId = clean(topicId);
            this.name = clean(name);
            this.fence = clean(fence);
            this.weight = weight;
            this.direct = direct;
            this.sources = sources;
            if (placement == null || !placement.getTopicId().equals(this.topicId)) {
                throw new IllegalArgumentException("a topic line needs the placement of its topic: " + topicId);
            }
            this.placement = placement;
        }

        public String getTopicId() {
            return topicId;
        }

        public String getName() {
            return name;
        }

        /** The fence verdict ({@code INNEN}, {@code AUSSEN}, {@code UNKLAR}, {@code UNGEPRUEFT}). */
        public String getFence() {
            return fence;
        }

        /** Direct evidence plus independent sources; 0 for a topic without direct evidence. */
        public long getWeight() {
            return weight;
        }

        public int getDirect() {
            return direct;
        }

        public int getSources() {
            return sources;
        }

        public Placement getPlacement() {
            return placement;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("themaId", topicId);
            json.addProperty("name", name);
            json.addProperty("zaun", fence);
            json.addProperty("gewicht", weight);
            json.addProperty("direkteBelege", direct);
            json.addProperty("unabhaengigeQuellen", sources);
            json.addProperty("verbleib", placement.getFate().name());
            json.addProperty("abschnittId", placement.getSectionId());
            json.addProperty("grund", placement.getReason());
            json.add("passagen", array(placement.getPassageIds()));
            return json;
        }

        static TopicLine fromJson(JsonObject json) {
            String id = string(json, "themaId");
            String section = string(json, "abschnittId");
            String reason = string(json, "grund");
            List<String> passages = strings(json, "passagen");
            Placement placement;
            String fate = string(json, "verbleib");
            if (Placement.Fate.EIGENER_ABSCHNITT.name().equals(fate)) {
                placement = Placement.ownSection(id, section, passages);
            } else if (Placement.Fate.AUFGEGANGEN_IN.name().equals(fate)) {
                placement = Placement.mergedInto(id, section, reason, passages);
            } else {
                placement = Placement.notTaken(id, reason.isEmpty() ? "ohne Grund gespeichert" : reason);
            }
            return new TopicLine(id, string(json, "name"), string(json, "zaun"),
                    number(json, "gewicht"), (int) number(json, "direkteBelege"),
                    (int) number(json, "unabhaengigeQuellen"), placement);
        }
    }

    private final int version;
    private final long timeMillis;
    private final int targetWords;
    private final boolean targetGiven;
    private final List<Chapter> chapters;
    private final List<TopicLine> topics;
    private final List<String> notes;
    private final String model;
    private final int wordsPerPassage;

    /**
     * @param targetWords the words the plan spreads: the length asked for, or the stand-in when none was asked
     * @param targetGiven whether the mission gave the length
     * @param model       the model that chose sections for topics without their own, {@code ""} for none
     */
    public SectionPlan(int version, long timeMillis, int targetWords, boolean targetGiven, List<Chapter> chapters,
                       List<TopicLine> topics, List<String> notes, String model) {
        this(version, timeMillis, targetWords, targetGiven, chapters, topics, notes, model, 0);
    }

    /**
     * @param wordsPerPassage the most words a section plans per passage of its material (direct evidence and leads),
     *                        0 for no such bound
     */
    public SectionPlan(int version, long timeMillis, int targetWords, boolean targetGiven, List<Chapter> chapters,
                       List<TopicLine> topics, List<String> notes, String model, int wordsPerPassage) {
        this.version = version;
        this.timeMillis = timeMillis;
        this.targetWords = targetWords;
        this.targetGiven = targetGiven;
        this.chapters = copy(chapters);
        this.topics = copy(topics);
        List<String> kept = new ArrayList<String>();
        for (String note : notes == null ? Collections.<String>emptyList() : notes) {
            if (!clean(note).isEmpty()) {
                kept.add(clean(note));
            }
        }
        this.notes = Collections.unmodifiableList(kept);
        this.model = clean(model);
        this.wordsPerPassage = Math.max(0, wordsPerPassage);
    }

    public int getVersion() {
        return version;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    public int getTargetWords() {
        return targetWords;
    }

    public boolean isTargetGiven() {
        return targetGiven;
    }

    public List<Chapter> getChapters() {
        return chapters;
    }

    /** Every topic of the memory, merged-away ones included, with its fate. */
    public List<TopicLine> getTopics() {
        return topics;
    }

    /** What the plan wants the reader to know (a stand-in length, a share without a chapter, a failed choice). */
    public List<String> getNotes() {
        return notes;
    }

    public String getModel() {
        return model;
    }

    /** The most words a section plans per passage of its material; 0 when the plan sets no such bound. */
    public int getWordsPerPassage() {
        return wordsPerPassage;
    }

    /** The fates, for the mission report (component 18). */
    public List<Placement> getPlacements() {
        List<Placement> placements = new ArrayList<Placement>();
        for (TopicLine line : topics) {
            placements.add(line.getPlacement());
        }
        return placements;
    }

    /** The sections in book order. */
    public List<Section> getSections() {
        List<Section> all = new ArrayList<Section>();
        for (Chapter chapter : chapters) {
            all.addAll(chapter.getSections());
        }
        return all;
    }

    /** The section with the id, {@code null} when the plan has none. */
    public Section section(String sectionId) {
        for (Section section : getSections()) {
            if (section.getId().equals(sectionId)) {
                return section;
            }
        }
        return null;
    }

    /** The line of a topic id, {@code null} when the plan has none. */
    public TopicLine topic(String topicId) {
        for (TopicLine line : topics) {
            if (line.getTopicId().equals(topicId)) {
                return line;
            }
        }
        return null;
    }

    /** The words of all sections. */
    public int getPlannedWords() {
        int sum = 0;
        for (Section section : getSections()) {
            sum += section.getWords();
        }
        return sum;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("version", version);
        json.addProperty("zeit", timeMillis);
        json.addProperty("zielwoerter", targetWords);
        json.addProperty("zielwoerterVorgegeben", targetGiven);
        json.addProperty("geplanteWoerter", getPlannedWords());
        json.addProperty("fehlendeWoerter", Math.max(0, targetWords - getPlannedWords()));
        json.addProperty("woerterJePassage", wordsPerPassage);
        json.addProperty("modell", model);
        JsonArray list = new JsonArray();
        for (Chapter chapter : chapters) {
            list.add(chapter.toJson());
        }
        json.add("kapitel", list);
        JsonArray lines = new JsonArray();
        for (TopicLine line : topics) {
            lines.add(line.toJson());
        }
        json.add("themen", lines);
        json.add("vermerke", array(notes));
        return json;
    }

    public static SectionPlan fromJson(JsonObject json) {
        List<Chapter> list = new ArrayList<Chapter>();
        for (JsonObject c : objects(json, "kapitel")) {
            list.add(Chapter.fromJson(c));
        }
        List<TopicLine> lines = new ArrayList<TopicLine>();
        for (JsonObject t : objects(json, "themen")) {
            lines.add(TopicLine.fromJson(t));
        }
        return new SectionPlan((int) number(json, "version"), number(json, "zeit"), (int) number(json, "zielwoerter"),
                json.has("zielwoerterVorgegeben") && json.get("zielwoerterVorgegeben").getAsBoolean(), list, lines,
                strings(json, "vermerke"), string(json, "modell"), (int) number(json, "woerterJePassage"));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> ids(List<String> values) {
        LinkedHashSet<String> kept = new LinkedHashSet<String>();
        for (String value : values == null ? Collections.<String>emptyList() : values) {
            if (!clean(value).isEmpty()) {
                kept.add(clean(value));
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(kept));
    }

    private static <T> List<T> copy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values == null ? Collections.<T>emptyList() : values));
    }

    private static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static List<String> strings(JsonObject json, String key) {
        List<String> values = new ArrayList<String>();
        JsonElement value = json.get(key);
        if (value != null && value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static List<JsonObject> objects(JsonObject json, String key) {
        List<JsonObject> values = new ArrayList<JsonObject>();
        JsonElement value = json.get(key);
        if (value != null && value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                values.add(element.getAsJsonObject());
            }
        }
        return values;
    }
}
