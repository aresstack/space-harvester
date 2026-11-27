package com.aresstack.askai.research.runtime.sourcerating;

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

    SourceRating(String sourceId, String address, String host, String title, Kind kind, Jurisdiction jurisdiction,
                 String date, SelfInterest selfInterest, String publisher, String reason,
                 KnownDomains.Entry suggestion, String model, String time, Status status, String problem,
                 String sampleId, List<String> answers) {
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
                sampleId, answers);
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
                + selfInterest + (date.isEmpty() ? "" : ", " + date) : "FEHLER " + problem);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
