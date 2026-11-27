package com.aresstack.writing4j.missionreport;

import com.aresstack.writing4j.memory.QuestionEvidence;
import com.aresstack.writing4j.memory.ResearchQuestion;
import com.aresstack.writing4j.memory.ResearchTopic;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Verdict;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The mission report (component 18): what the stored data and the text of the book hold, listed against the mission.
 * Every topic with its fate, its covered sentences and the reason, every question with its direct evidence, leads or "Kein Beleg gefunden", the open contradictions, the kinds
 * of sources the text cites, and the length asked for and written.
 * <p>
 * A topic counts as treated ("behandelt") only with at least one sentence of the text that the sentence check
 * (component 11) judged GEDECKT and that cites a passage linked to the topic; a heading alone does not count. The
 * report lists; it never says that the book or the research is done, and it judges nothing. The user releases the
 * book, not the report. Built by {@link MissionCheck}, without a language model.
 */
public final class MissionReport {

    /** Sentences by the sentence check's verdict. */
    public static final class Counts {
        private int sentences;
        private int covered;
        private int partial;
        private int notCovered;
        private int unchecked;
        private int uncited;

        void add(Verdict verdict, boolean cited) {
            sentences++;
            if (!cited) {
                uncited++;
            } else if (verdict == Verdict.GEDECKT) {
                covered++;
            } else if (verdict == Verdict.TEILWEISE) {
                partial++;
            } else if (verdict == Verdict.NICHT_GEDECKT) {
                notCovered++;
            } else {
                unchecked++;
            }
        }

        public int getSentences() {
            return sentences;
        }

        public int getCovered() {
            return covered;
        }

        public int getPartial() {
            return partial;
        }

        public int getNotCovered() {
            return notCovered;
        }

        /** Cited sentences without a verdict on every passage they cite (or with a failed check). */
        public int getUnchecked() {
            return unchecked;
        }

        /** Sentences without a marker that names a passage. */
        public int getUncited() {
            return uncited;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("saetze", sentences);
            json.addProperty("gedeckt", covered);
            json.addProperty("teilweise", partial);
            json.addProperty("nichtGedeckt", notCovered);
            json.addProperty("nichtGeprueft", unchecked);
            json.addProperty("ohneQuellenangabe", uncited);
            return json;
        }

        String describe() {
            StringBuilder out = new StringBuilder(number(sentences)).append(sentences == 1 ? " Satz" : " Sätze");
            if (sentences == 0) {
                return out.toString();
            }
            out.append(": ").append(number(covered)).append(" gedeckt, ").append(number(partial))
                    .append(" teilweise, ").append(number(notCovered)).append(" nicht gedeckt, ")
                    .append(number(unchecked)).append(" nicht geprüft");
            if (uncited > 0) {
                out.append(", ").append(number(uncited)).append(" ohne Quellenangabe");
            }
            return out.toString();
        }
    }

    /** One section of the text: the words it was written for and has, and its sentences. */
    public static final class SectionLine {
        private final String sectionId;
        private final String title;
        private final int targetWords;
        private final int writtenWords;
        private final Counts counts;

        SectionLine(String sectionId, String title, int targetWords, int writtenWords, Counts counts) {
            this.sectionId = sectionId;
            this.title = title;
            this.targetWords = targetWords;
            this.writtenWords = writtenWords;
            this.counts = counts;
        }

        public String getSectionId() {
            return sectionId;
        }

        public String getTitle() {
            return title;
        }

        public int getTargetWords() {
            return targetWords;
        }

        public int getWrittenWords() {
            return writtenWords;
        }

        public Counts getCounts() {
            return counts;
        }
    }

    /** One topic: its fate in the section plan, its fence, its evidence and the sentences linked to it. */
    public static final class TopicLine {
        private final ResearchTopic topic;
        private final Placement placement;
        private final String sectionTitle;
        private final int questions;
        private final int direct;
        private final int leads;
        private final int sources;
        private final int findings;
        private final Counts counts;

        TopicLine(ResearchTopic topic, Placement placement, String sectionTitle, int questions, int direct, int leads,
                  int sources, int findings, Counts counts) {
            this.topic = topic;
            this.placement = placement;
            this.sectionTitle = sectionTitle;
            this.questions = questions;
            this.direct = direct;
            this.leads = leads;
            this.sources = sources;
            this.findings = findings;
            this.counts = counts;
        }

        public ResearchTopic getTopic() {
            return topic;
        }

        /** The fate the section plan gave the topic, {@code null} when it names none. */
        public Placement getPlacement() {
            return placement;
        }

        /** The title of the placement's section, its id when the text has no such section; empty without one. */
        public String getSectionTitle() {
            return sectionTitle;
        }

        public int getQuestions() {
            return questions;
        }

        public int getDirect() {
            return direct;
        }

        public int getLeads() {
            return leads;
        }

        public int getSources() {
            return sources;
        }

        public int getFindings() {
            return findings;
        }

        /** The cited sentences of the text linked to the topic. */
        public Counts getCounts() {
            return counts;
        }

        /** At least one sentence of the text linked to the topic is covered. */
        public boolean isTreated() {
            return counts.getCovered() > 0;
        }
    }

    /** One question: what the memory holds for it and the sentences of the text that cite its evidence. */
    public static final class QuestionLine {
        private final QuestionEvidence evidence;
        private final String topicName;
        private final int citedSentences;
        private final int coveredSentences;

        QuestionLine(QuestionEvidence evidence, String topicName, int citedSentences, int coveredSentences) {
            this.evidence = evidence;
            this.topicName = topicName;
            this.citedSentences = citedSentences;
            this.coveredSentences = coveredSentences;
        }

        public QuestionEvidence getEvidence() {
            return evidence;
        }

        public ResearchQuestion getQuestion() {
            return evidence.getQuestion();
        }

        public String getTopicName() {
            return topicName;
        }

        /** Sentences of the text that cite a direct evidence or a lead of the question. */
        public int getCitedSentences() {
            return citedSentences;
        }

        /** Those of them the sentence check judged GEDECKT. */
        public int getCoveredSentences() {
            return coveredSentences;
        }
    }

    /** One open contradiction between two passages on a question or a topic. */
    public static final class ContradictionLine {
        private final MissionCheck.ContradictionRecord record;
        private final String scopeName;

        ContradictionLine(MissionCheck.ContradictionRecord record, String scopeName) {
            this.record = record;
            this.scopeName = scopeName;
        }

        public MissionCheck.ContradictionRecord getRecord() {
            return record;
        }

        /** The question's text or the topic's name, the id when the memory does not know it. */
        public String getScopeName() {
            return scopeName;
        }
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'",
            Locale.GERMANY);

    private final long timeMillis;
    private final int requestedMinWords;
    private final int requestedMaxWords;
    private final int writtenWords;
    private final List<SectionLine> sections;
    private final Counts sentences;
    private final int statedAsFact;
    private final int foreignRuleUnmarked;
    private final int unknownMarkers;
    private final List<TopicLine> foundTopics;
    private final List<String> unknownPlacements;
    private final List<QuestionLine> questions;
    private final List<ContradictionLine> openContradictions;
    private final Map<String, Integer> contradictionStates;
    private final boolean contradictionsChecked;
    private final int citations;
    private final Map<String, Integer> citationsByKind;
    private final Map<String, Integer> citationsByJurisdiction;
    private final int citedSources;

    MissionReport(long timeMillis, int requestedMinWords, int requestedMaxWords, int writtenWords,
                  List<SectionLine> sections, Counts sentences, int statedAsFact, int foreignRuleUnmarked,
                  int unknownMarkers, List<TopicLine> foundTopics,
                  List<String> unknownPlacements, List<QuestionLine> questions,
                  List<ContradictionLine> openContradictions, Map<String, Integer> contradictionStates,
                  boolean contradictionsChecked, int citations, Map<String, Integer> citationsByKind,
                  Map<String, Integer> citationsByJurisdiction, int citedSources) {
        this.timeMillis = timeMillis;
        this.requestedMinWords = requestedMinWords;
        this.requestedMaxWords = requestedMaxWords;
        this.writtenWords = writtenWords;
        this.sections = Collections.unmodifiableList(new ArrayList<SectionLine>(sections));
        this.sentences = sentences;
        this.statedAsFact = statedAsFact;
        this.foreignRuleUnmarked = foreignRuleUnmarked;
        this.unknownMarkers = unknownMarkers;
        this.foundTopics = Collections.unmodifiableList(new ArrayList<TopicLine>(foundTopics));
        this.unknownPlacements = Collections.unmodifiableList(new ArrayList<String>(unknownPlacements));
        this.questions = Collections.unmodifiableList(new ArrayList<QuestionLine>(questions));
        this.openContradictions = Collections.unmodifiableList(new ArrayList<ContradictionLine>(openContradictions));
        this.contradictionStates = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(contradictionStates));
        this.contradictionsChecked = contradictionsChecked;
        this.citations = citations;
        this.citationsByKind = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(citationsByKind));
        this.citationsByJurisdiction = Collections.unmodifiableMap(
                new LinkedHashMap<String, Integer>(citationsByJurisdiction));
        this.citedSources = citedSources;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    /** The fewest words the mission asks for, 0 when it names no length. */
    public int getRequestedMinWords() {
        return requestedMinWords;
    }

    /** The most words the mission asks for, 0 when it names no length. */
    public int getRequestedMaxWords() {
        return requestedMaxWords;
    }

    /** The words of the text, markers not counted. */
    public int getWrittenWords() {
        return writtenWords;
    }

    public List<SectionLine> getSections() {
        return sections;
    }

    /** Every sentence of the text. */
    public Counts getSentences() {
        return sentences;
    }

    /** Sentences that state a claim of a maker, trader or association as a fact. */
    public int getStatedAsFact() {
        return statedAsFact;
    }

    /** Sentences that give a rule of another legal space without saying where it holds. */
    public int getForeignRuleUnmarked() {
        return foreignRuleUnmarked;
    }

    /** Markers of the text whose section names no passage for them. */
    public int getUnknownMarkers() {
        return unknownMarkers;
    }

    public List<TopicLine> getFoundTopics() {
        return foundTopics;
    }

    /** Topic ids the section plan names that the memory does not know. */
    public List<String> getUnknownPlacements() {
        return unknownPlacements;
    }

    public List<QuestionLine> getQuestions() {
        return questions;
    }

    public List<ContradictionLine> getOpenContradictions() {
        return openContradictions;
    }

    /** The contradictions that are no longer open or never were, by state or verdict. */
    public Map<String, Integer> getContradictionStates() {
        return contradictionStates;
    }

    /** Whether the contradiction check delivered anything at all (an empty list counts). */
    public boolean isContradictionsChecked() {
        return contradictionsChecked;
    }

    /** The sentence and passage pairs the markers of the text name. */
    public int getCitations() {
        return citations;
    }

    /** {@link #getCitations()} by the source's kind; {@link MissionCheck#UNRATED} for a source without a rating. */
    public Map<String, Integer> getCitationsByKind() {
        return citationsByKind;
    }

    public Map<String, Integer> getCitationsByJurisdiction() {
        return citationsByJurisdiction;
    }

    /** The different sources the text cites. */
    public int getCitedSources() {
        return citedSources;
    }

    // ------------------------------------------------------------------------------------------------- text

    /** The report as a plain German text, dated in the given zone. */
    public String text(ZoneId zone) {
        StringBuilder out = new StringBuilder();
        out.append("Auftragsabgleich vom ").append(DATE.format(Instant.ofEpochMilli(timeMillis).atZone(zone)))
                .append("\n\n");
        out.append("Dieser Bericht listet, was gespeichert ist und was im Text steht. Er bewertet nicht; die Freigabe "
                + "des Buchs liegt beim Nutzer.\n");
        out.append("„Behandelt“ heißt: mindestens ein Satz im Text, den der Satzprüfer als gedeckt beurteilt hat und "
                + "der eine Passage des Themas zitiert. Eine Überschrift allein zählt nicht.\n");

        out.append("\nLänge\n");
        out.append("  Verlangt: ").append(requested()).append(". Geschrieben: ").append(number(writtenWords))
                .append(" Wörter in ").append(number(sections.size()))
                .append(sections.size() == 1 ? " Abschnitt.\n" : " Abschnitten.\n");
        for (SectionLine section : sections) {
            out.append("  ").append(quoted(section.getTitle().isEmpty() ? section.getSectionId() : section.getTitle()))
                    .append(": ");
            if (section.getTargetWords() > 0) {
                out.append("Ziel ").append(number(section.getTargetWords())).append(" Wörter, ");
            }
            out.append("geschrieben ").append(number(section.getWrittenWords())).append("; ")
                    .append(section.getCounts().describe()).append('\n');
        }

        out.append("\nSätze\n");
        out.append("  ").append(sentences.describe()).append(".\n");
        out.append("  Aussagen von Herstellern, Händlern oder Verbänden als Tatsache wiedergegeben: ")
                .append(number(statedAsFact)).append(". Regeln eines anderen Rechtsraums ohne Angabe, wo sie gelten: ")
                .append(number(foreignRuleUnmarked)).append(".\n");
        if (unknownMarkers > 0) {
            out.append("  Quellenangaben ohne Passage: ").append(number(unknownMarkers)).append(".\n");
        }

        out.append("\nThemen (").append(number(foundTopics.size())).append(")\n");
        appendTopics(out, foundTopics);
        if (!unknownPlacements.isEmpty()) {
            out.append("  Der Abschnittsplan nennt ").append(number(unknownPlacements.size()))
                    .append(unknownPlacements.size() == 1 ? " Thema, das" : " Themen, die")
                    .append(" das Gedächtnis nicht kennt: ").append(String.join(", ", unknownPlacements)).append(".\n");
        }

        out.append("\nFragen (").append(number(questions.size())).append(")\n");
        for (QuestionLine line : questions) {
            ResearchQuestion question = line.getQuestion();
            QuestionEvidence evidence = line.getEvidence();
            out.append("  ").append(question.getId()).append(' ').append(question.getText());
            if (!line.getTopicName().isEmpty()) {
                out.append(" [").append(line.getTopicName()).append(']');
            }
            if (question.getState() == ResearchQuestion.State.ZURUECKGESTELLT) {
                out.append(" (zurückgestellt)");
            }
            out.append(": ").append(evidence.summary());
            if (evidence.getDirectCount() == 0 && evidence.getLeadCount() == 0) {
                out.append(" (").append(number(evidence.getJudgedPassages()))
                        .append(evidence.getJudgedPassages() == 1 ? " Passage geprüft)" : " Passagen geprüft)");
            }
            out.append(". Im Text zitiert: ").append(number(line.getCitedSentences()))
                    .append(line.getCitedSentences() == 1 ? " Satz" : " Sätze");
            if (line.getCitedSentences() > 0) {
                out.append(", davon ").append(number(line.getCoveredSentences())).append(" gedeckt");
            }
            out.append('.');
            if (evidence.getErrors() > 0) {
                out.append(" Fehler bei ").append(number(evidence.getErrors()))
                        .append(evidence.getErrors() == 1 ? " Passage, sie wird" : " Passagen, sie werden")
                        .append(" erneut geprüft.");
            }
            out.append('\n');
        }

        out.append("\nOffene Widersprüche (").append(number(openContradictions.size())).append(")\n");
        if (!contradictionsChecked) {
            out.append("  Keine Widerspruchsprüfung gespeichert.\n");
        } else if (openContradictions.isEmpty()) {
            out.append("  Keine offenen Widersprüche gespeichert.\n");
        }
        for (ContradictionLine line : openContradictions) {
            MissionCheck.ContradictionRecord record = line.getRecord();
            out.append("  ").append("THEMA".equals(record.getScope()) ? "Thema " : "Frage ")
                    .append(quoted(line.getScopeName())).append(": ").append(record.getSourceA()).append(" und ")
                    .append(record.getSourceB()).append(" (")
                    .append("UNKLAR".equals(record.getVerdict()) ? "unklar" : "Widerspruch").append(")\n");
        }
        if (!contradictionStates.isEmpty()) {
            out.append("  Sonst: ");
            List<String> parts = new ArrayList<String>();
            for (Map.Entry<String, Integer> entry : contradictionStates.entrySet()) {
                parts.add(stateName(entry.getKey()) + " " + number(entry.getValue()));
            }
            out.append(String.join(", ", parts)).append(".\n");
        }

        out.append("\nQuellenarten im Text\n");
        out.append("  ").append(number(citations)).append(citations == 1 ? " Quellenangabe" : " Quellenangaben")
                .append(" aus ").append(number(citedSources)).append(citedSources == 1 ? " Quelle" : " Quellen");
        out.append(citations == 0 ? ".\n" : ": " + shares(citationsByKind) + ".\n");
        if (!citationsByJurisdiction.isEmpty()) {
            out.append("  Rechtsraum: ").append(shares(citationsByJurisdiction)).append(".\n");
        }
        return out.toString();
    }

    private void appendTopics(StringBuilder out, List<TopicLine> topics) {
        for (TopicLine line : topics) {
            ResearchTopic topic = line.getTopic();
            Counts counts = line.getCounts();
            out.append("  ").append(topic.getName()).append(": ");
            if (line.isTreated()) {
                out.append("behandelt, ").append(number(counts.getCovered()))
                        .append(counts.getCovered() == 1 ? " gedeckter Satz" : " gedeckte Sätze");
            } else {
                out.append("nicht behandelt, kein gedeckter Satz");
            }
            out.append(" (").append(number(counts.getPartial())).append(" teilweise, ")
                    .append(number(counts.getNotCovered())).append(" nicht gedeckt, ")
                    .append(number(counts.getUnchecked())).append(" nicht geprüft). ");
            out.append(fate(line)).append(". ");
            out.append("Zaun: ").append(fence(topic)).append(". ");
            if (!topic.getMergedFrom().isEmpty()) {
                out.append("Zusammengeführt aus ").append(number(topic.getMergedFrom().size() + 1))
                        .append(" Vorschlägen. ");
            }
            out.append("Fundstellen: ").append(number(line.getFindings())).append(". ");
            out.append(number(line.getQuestions())).append(line.getQuestions() == 1 ? " Frage; " : " Fragen; ");
            if (line.getDirect() == 0 && line.getLeads() == 0) {
                out.append(QuestionEvidence.LABEL_NONE);
            } else {
                out.append(QuestionEvidence.LABEL_DIRECT).append(": ").append(number(line.getDirect()));
                if (line.getDirect() > 0) {
                    out.append(" (").append(number(line.getSources()))
                            .append(line.getSources() == 1 ? " Quelle)" : " Quellen)");
                }
                out.append(" · ").append(QuestionEvidence.LABEL_LEAD).append(": ").append(number(line.getLeads()));
            }
            out.append(".\n");
        }
    }

    static String fate(TopicLine line) {
        Placement placement = line.getPlacement();
        ResearchTopic topic = line.getTopic();
        if (placement == null) {
            if (topic.getFence() == ResearchTopic.Fence.AUSSEN) {
                return "Nicht im Buch: außerhalb des Weidezauns" + reason(topic.getFenceReason());
            }
            if (topic.getFence() == ResearchTopic.Fence.UNKLAR) {
                return "Nicht im Buch: Zugehörigkeit unklar, wartet auf eine Entscheidung"
                        + reason(topic.getFenceReason());
            }
            return "Kein Verbleib im Abschnittsplan vermerkt";
        }
        switch (placement.getFate()) {
            case EIGENER_ABSCHNITT:
                return "Eigener Abschnitt " + quoted(line.getSectionTitle());
            case AUFGEGANGEN_IN:
                return "Aufgegangen in " + quoted(line.getSectionTitle()) + reason(placement.getReason());
            default:
                return "Nicht aufgenommen: " + placement.getReason();
        }
    }

    private static String fence(ResearchTopic topic) {
        switch (topic.getFence()) {
            case INNEN:
                return "innen";
            case AUSSEN:
                return "außen";
            case UNKLAR:
                return "unklar";
            default:
                return "ungeprüft";
        }
    }

    private static String reason(String reason) {
        return reason == null || reason.trim().isEmpty() ? "" : " (" + reason.trim() + ")";
    }

    private static String stateName(String state) {
        switch (state) {
            case "IM_TEXT_DARGESTELLT":
                return "im Text dargestellt";
            case "VOM_NUTZER_ENTSCHIEDEN":
                return "vom Nutzer entschieden";
            case "VEREINBAR":
                return "vereinbar";
            case "UNBEURTEILT":
                return "nicht beurteilt";
            default:
                return state.toLowerCase(Locale.ROOT);
        }
    }

    private String requested() {
        if (requestedMinWords <= 0 && requestedMaxWords <= 0) {
            return "keine Länge angegeben";
        }
        if (requestedMinWords > 0 && requestedMaxWords > 0 && requestedMinWords != requestedMaxWords) {
            return number(requestedMinWords) + " bis " + number(requestedMaxWords) + " Wörter";
        }
        return number(Math.max(requestedMinWords, requestedMaxWords)) + " Wörter";
    }

    private String shares(Map<String, Integer> counts) {
        int total = 0;
        for (int count : counts.values()) {
            total += count;
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            long percent = total == 0 ? 0 : Math.round(entry.getValue() * 100.0 / total);
            parts.add(entry.getKey() + " " + number(entry.getValue()) + " (" + percent + " %)");
        }
        return String.join(", ", parts);
    }

    static String number(int value) {
        return String.format(Locale.GERMANY, "%,d", value);
    }

    private static String quoted(String text) {
        return "„" + text + "“";
    }

    // ------------------------------------------------------------------------------------------------- JSON

    /** The report as JSON: ids, names, question texts and counts, no sentence and no passage text. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("zeit", timeMillis);
        JsonObject length = new JsonObject();
        length.addProperty("verlangtMin", requestedMinWords);
        length.addProperty("verlangtMax", requestedMaxWords);
        length.addProperty("geschrieben", writtenWords);
        JsonArray sectionArray = new JsonArray();
        for (SectionLine section : sections) {
            JsonObject s = section.getCounts().toJson();
            s.addProperty("abschnitt", section.getSectionId());
            s.addProperty("titel", section.getTitle());
            s.addProperty("zielWoerter", section.getTargetWords());
            s.addProperty("woerter", section.getWrittenWords());
            sectionArray.add(s);
        }
        length.add("abschnitte", sectionArray);
        json.add("laenge", length);
        JsonObject sentenceJson = sentences.toJson();
        sentenceJson.addProperty("alsTatsache", statedAsFact);
        sentenceJson.addProperty("fremdeRegelOhneAngabe", foreignRuleUnmarked);
        sentenceJson.addProperty("quellenangabenOhnePassage", unknownMarkers);
        json.add("saetze", sentenceJson);
        json.add("themen", topicsJson(foundTopics));
        JsonArray unknown = new JsonArray();
        for (String id : unknownPlacements) {
            unknown.add(id);
        }
        json.add("unbekannteThemenImPlan", unknown);
        JsonArray questionArray = new JsonArray();
        for (QuestionLine line : questions) {
            QuestionEvidence evidence = line.getEvidence();
            JsonObject q = new JsonObject();
            q.addProperty("frage", line.getQuestion().getId());
            q.addProperty("text", line.getQuestion().getText());
            q.addProperty("thema", line.getQuestion().getTopicId());
            q.addProperty("herkunft", line.getQuestion().getOrigin().name());
            q.addProperty("zustand", line.getQuestion().getState().name());
            q.addProperty("stand", evidence.getDirectCount() > 0 ? "BELEGT"
                    : evidence.getLeadCount() > 0 ? "HINWEIS" : "KEIN_BELEG");
            for (Map.Entry<String, Integer> count : evidence.counts().entrySet()) {
                q.addProperty(count.getKey(), count.getValue());
            }
            q.addProperty("zitiertInSaetzen", line.getCitedSentences());
            q.addProperty("davonGedeckt", line.getCoveredSentences());
            questionArray.add(q);
        }
        json.add("fragen", questionArray);
        JsonObject contradictions = new JsonObject();
        contradictions.addProperty("geprueft", contradictionsChecked);
        JsonArray open = new JsonArray();
        for (ContradictionLine line : openContradictions) {
            MissionCheck.ContradictionRecord record = line.getRecord();
            JsonObject c = new JsonObject();
            c.addProperty("id", record.getId());
            c.addProperty("bereich", record.getScope());
            c.addProperty("bereichId", record.getScopeId());
            c.addProperty("quelleA", record.getSourceA());
            c.addProperty("quelleB", record.getSourceB());
            c.addProperty("urteil", record.getVerdict());
            open.add(c);
        }
        contradictions.add("offen", open);
        JsonObject states = new JsonObject();
        for (Map.Entry<String, Integer> entry : contradictionStates.entrySet()) {
            states.addProperty(entry.getKey(), entry.getValue());
        }
        contradictions.add("sonst", states);
        json.add("widersprueche", contradictions);
        JsonObject mix = new JsonObject();
        mix.addProperty("quellenangaben", citations);
        mix.addProperty("quellen", citedSources);
        mix.add("jeArt", counts(citationsByKind));
        mix.add("jeRechtsraum", counts(citationsByJurisdiction));
        json.add("quellenarten", mix);
        return json;
    }

    private static JsonArray topicsJson(List<TopicLine> topics) {
        JsonArray array = new JsonArray();
        for (TopicLine line : topics) {
            ResearchTopic topic = line.getTopic();
            JsonObject t = line.getCounts().toJson();
            t.addProperty("thema", topic.getId());
            t.addProperty("name", topic.getName());
            t.addProperty("zaun", topic.getFence().name());
            t.addProperty("behandelt", line.isTreated());
            Placement placement = line.getPlacement();
            t.addProperty("verbleib", placement == null ? "" : placement.getFate().name());
            t.addProperty("abschnitt", placement == null ? "" : placement.getSectionId());
            t.addProperty("grund", placement == null ? topic.getFenceReason() : placement.getReason());
            t.addProperty("zusammengefuehrt", topic.getMergedFrom().size());
            t.addProperty("fragen", line.getQuestions());
            t.addProperty("direkt", line.getDirect());
            t.addProperty("hinweise", line.getLeads());
            t.addProperty("quellen", line.getSources());
            t.addProperty("fundstellen", line.getFindings());
            array.add(t);
        }
        return array;
    }

    private static JsonObject counts(Map<String, Integer> counts) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }
}
