package com.aresstack.writing4j.sourcerating;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Component 16: the data type "Quelle" of the build plan (rev 13, 21.11.2026). It describes a source and filters
 * nothing: whether a source fits the mission stays the business of the evidence judge (6) and the fence check (14).
 * The writer (10) attributes statements of manufacturers, dealers and associations instead of stating them as fact,
 * and marks legal statements of another jurisdiction; the sentence check (11), the contradictions (17) and the mission
 * report (18) read kind and jurisdiction too. A rating is made once per source group (the sources of one host) and
 * copied to every source of the group. Immutable.
 */
public final class SourceRating {

    /** What kind of publisher the source is (Quellenart). */
    public enum Kind {
        GESETZ_NORM, BEHOERDE_UNFALLVERSICHERUNG, WISSENSCHAFT, FACHPRESSE, KANZLEI_BERATUNG, HERSTELLER_HANDEL,
        VERBAND, NACHSCHLAGEWERK, SONSTIGE
    }

    /** For whose law and authorities the source speaks (Rechtsraum). */
    public enum Jurisdiction {
        DE, AT, CH, EU, ANDERE, OHNE
    }

    /** Whether the publisher sells what the text is about (Eigeninteresse). */
    public enum SelfInterest {
        JA, NEIN, UNKLAR
    }

    /**
     * How the source relates to the Weidezaun of the book (only when the rating was asked with one): wholly inside,
     * mixed (parts inside, parts outside), clearly outside, or unclear. A mixed source is kept whole; only a source
     * clearly outside is left out of the evidence, and an unclear one stays.
     */
    public enum Relation {
        INNEN, GEMISCHT, AUSSEN, UNKLAR
    }

    /**
     * The document level of the source profile against the Weidezaun: what the source is about in one sentence, its
     * relation to the fence and, for a mixed source, the headings of the parts outside. {@link #NONE} when the rating
     * was asked without a fence. Immutable.
     */
    public static final class Fit {
        public static final Fit NONE = new Fit("", null, null);

        private final String subject;
        private final Relation relation;
        private final List<String> outsideParts;

        public Fit(String subject, Relation relation, List<String> outsideParts) {
            this.subject = subject == null ? "" : subject.trim();
            this.relation = relation;
            this.outsideParts = outsideParts == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(outsideParts));
        }

        public String getSubject() {
            return subject;
        }

        /** {@code null} when the rating was asked without a fence. */
        public Relation getRelation() {
            return relation;
        }

        public List<String> getOutsideParts() {
            return outsideParts;
        }

        public boolean isAsked() {
            return relation != null;
        }
    }

    /** Whether the rating holds or must be offered again. */
    public enum Status {
        OK, FEHLER
    }

    private final String sourceId;
    private final String address;
    private final String host;
    private final String title;
    private final Kind kind;
    private final Jurisdiction jurisdiction;
    private final String date;
    private final SelfInterest selfInterest;
    private final String publisher;
    private final String reason;
    private final KnownDomains.Entry suggestion;
    private final String model;
    private final String time;
    private final Status status;
    private final String problem;
    private final String sampleId;
    private final List<String> answers;
    private final Fit fit;

    SourceRating(String sourceId, String address, String host, String title, Kind kind, Jurisdiction jurisdiction,
                 String date, SelfInterest selfInterest, String publisher, String reason,
                 KnownDomains.Entry suggestion, String model, String time, Status status, String problem,
                 String sampleId, List<String> answers) {
        this(sourceId, address, host, title, kind, jurisdiction, date, selfInterest, publisher, reason, suggestion,
                model, time, status, problem, sampleId, answers, Fit.NONE);
    }

    SourceRating(String sourceId, String address, String host, String title, Kind kind, Jurisdiction jurisdiction,
                 String date, SelfInterest selfInterest, String publisher, String reason,
                 KnownDomains.Entry suggestion, String model, String time, Status status, String problem,
                 String sampleId, List<String> answers, Fit fit) {
        this.fit = fit == null ? Fit.NONE : fit;
        this.sourceId = text(sourceId);
        this.address = text(address);
        this.host = text(host);
        this.title = text(title);
        this.kind = kind;
        this.jurisdiction = jurisdiction;
        this.date = text(date);
        this.selfInterest = selfInterest;
        this.publisher = text(publisher);
        this.reason = text(reason);
        this.suggestion = suggestion;
        this.model = text(model);
        this.time = text(time);
        this.status = status == null ? Status.FEHLER : status;
        this.problem = text(problem);
        this.sampleId = sampleId == null || sampleId.isEmpty() ? this.sourceId : sampleId;
        this.answers = answers == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(answers));
    }

    /**
     * The same verdict for another source of the group, without a call of its own. The date belongs to a page, not to
     * the group: the other source gets the one its own address shows, or none.
     */
    SourceRating forSource(SourceSample source, String ownDate) {
        return new SourceRating(source.getSourceId(), source.getAddress(), host, source.getTitle(), kind,
                jurisdiction, ownDate, selfInterest, publisher, reason, suggestion, model, time, status, problem,
                sampleId, answers, fit);
    }

    /** The document level against the Weidezaun; {@link Fit#NONE} when asked without a fence. */
    public Fit getFit() {
        return fit;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getAddress() {
        return address;
    }

    /** The source group: the host of the address without {@code www.}. */
    public String getHost() {
        return host;
    }

    public String getTitle() {
        return title;
    }

    /** {@code null} for {@link Status#FEHLER}. */
    public Kind getKind() {
        return kind;
    }

    /** {@code null} for {@link Status#FEHLER}. */
    public Jurisdiction getJurisdiction() {
        return jurisdiction;
    }

    /** The date of publication or of the state of the text ({@code JJJJ}, {@code JJJJ-MM} or {@code JJJJ-MM-TT}). */
    public String getDate() {
        return date;
    }

    /** {@code null} for {@link Status#FEHLER}. */
    public SelfInterest getSelfInterest() {
        return selfInterest;
    }

    /** The publisher as the model names it. */
    public String getPublisher() {
        return publisher;
    }

    /** The model's reason in one sentence. */
    public String getReason() {
        return reason;
    }

    /** What the list of known domains suggested, {@code null} when it knows the host not. */
    public KnownDomains.Entry getSuggestion() {
        return suggestion;
    }

    /** What the chat calls (the slot in the app, the model in a test). */
    public String getModel() {
        return model;
    }

    /** When the rating was made (ISO-8601). */
    public String getTime() {
        return time;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /** Why the rating failed; empty when it holds. */
    public String getProblem() {
        return problem;
    }

    /** The source of the group the model read; the own id when this source was read. */
    public String getSampleId() {
        return sampleId;
    }

    /** The raw answer of every call for the group, in order (a failed call adds ""). */
    public List<String> getAnswers() {
        return answers;
    }

    /**
     * Whether the writer must attribute statements of this source ("nach Angaben des Herstellers") instead of stating
     * them as fact: manufacturers, dealers and associations (build plan, users of component 16).
     */
    public boolean needsAttribution() {
        return kind == Kind.HERSTELLER_HANDEL || kind == Kind.VERBAND;
    }

    @Override
    public String toString() {
        return sourceId + " (" + host + "): " + (isOk() ? kind + ", " + jurisdiction + ", Eigeninteresse "
                + selfInterest + (date.isEmpty() ? "" : ", " + date)
                + (fit.isAsked() ? ", Weidezaun " + fit.getRelation() : "") : "FEHLER " + problem);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** This rating as JSON, for a {@link ProfileMemory}; {@link #fromJson} reads it back. */
    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("sourceId", sourceId);
        json.addProperty("address", address);
        json.addProperty("host", host);
        json.addProperty("title", title);
        json.addProperty("kind", kind == null ? null : kind.name());
        json.addProperty("jurisdiction", jurisdiction == null ? null : jurisdiction.name());
        json.addProperty("date", date);
        json.addProperty("selfInterest", selfInterest == null ? null : selfInterest.name());
        json.addProperty("publisher", publisher);
        json.addProperty("reason", reason);
        if (suggestion != null) {
            JsonObject entry = new JsonObject();
            entry.addProperty("domain", suggestion.getDomain());
            entry.addProperty("kind", suggestion.getKind() == null ? null : suggestion.getKind().name());
            entry.addProperty("jurisdiction", suggestion.getJurisdiction() == null ? null
                    : suggestion.getJurisdiction().name());
            entry.addProperty("note", suggestion.getNote());
            json.add("suggestion", entry);
        }
        json.addProperty("model", model);
        json.addProperty("time", time);
        json.addProperty("status", status.name());
        json.addProperty("problem", problem);
        json.addProperty("sampleId", sampleId);
        JsonArray said = new JsonArray();
        for (String answer : answers) {
            said.add(answer);
        }
        json.add("answers", said);
        JsonObject f = new JsonObject();
        f.addProperty("subject", fit.getSubject());
        f.addProperty("relation", fit.getRelation() == null ? null : fit.getRelation().name());
        JsonArray outside = new JsonArray();
        for (String part : fit.getOutsideParts()) {
            outside.add(part);
        }
        f.add("outsideParts", outside);
        json.add("fit", f);
        return json.toString();
    }

    /** A rating from {@link #toJson()}; {@code null} when the text is not one (unreadable or of another form). */
    public static SourceRating fromJson(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            KnownDomains.Entry suggestion = null;
            if (json.has("suggestion") && json.get("suggestion").isJsonObject()) {
                JsonObject entry = json.getAsJsonObject("suggestion");
                suggestion = new KnownDomains.Entry(string(entry, "domain"),
                        named(Kind.class, string(entry, "kind")), named(Jurisdiction.class,
                        string(entry, "jurisdiction")), string(entry, "note"));
            }
            Fit fit = Fit.NONE;
            if (json.has("fit") && json.get("fit").isJsonObject()) {
                JsonObject f = json.getAsJsonObject("fit");
                fit = new Fit(string(f, "subject"), named(Relation.class, string(f, "relation")),
                        strings(f, "outsideParts"));
            }
            return new SourceRating(string(json, "sourceId"), string(json, "address"), string(json, "host"),
                    string(json, "title"), named(Kind.class, string(json, "kind")),
                    named(Jurisdiction.class, string(json, "jurisdiction")), string(json, "date"),
                    named(SelfInterest.class, string(json, "selfInterest")), string(json, "publisher"),
                    string(json, "reason"), suggestion, string(json, "model"), string(json, "time"),
                    named(Status.class, string(json, "status")), string(json, "problem"),
                    string(json, "sampleId"), strings(json, "answers"), fit);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static List<String> strings(JsonObject json, String name) {
        List<String> out = new ArrayList<String>();
        JsonElement value = json.get(name);
        if (value != null && value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                out.add(item.isJsonNull() ? "" : item.getAsString());
            }
        }
        return out;
    }

    private static <E extends Enum<E>> E named(Class<E> type, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
