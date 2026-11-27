package com.aresstack.writing4j.missionreport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Where the section plan (component 9) put a topic: a section of its own, merged into a section, or not taken, with
 * the reason. The plan may also name the passages it gave the topic; the report links sentences that cite them to
 * the topic.
 */
public final class Placement {

    /** The fate of a topic in the section plan. */
    public enum Fate {
        /** The topic has a section of its own. */
        EIGENER_ABSCHNITT,
        /** The topic went into a section with others. */
        AUFGEGANGEN_IN,
        /** The topic is not in the book; the reason says why. */
        NICHT_AUFGENOMMEN
    }

    private final String topicId;
    private final Fate fate;
    private final String sectionId;
    private final String reason;
    private final List<String> passageIds;

    private Placement(String topicId, Fate fate, String sectionId, String reason, List<String> passageIds) {
        this.topicId = BookSection.clean(topicId);
        if (this.topicId.isEmpty()) {
            throw new IllegalArgumentException("a placement needs a topic id");
        }
        this.fate = fate;
        this.sectionId = BookSection.clean(sectionId);
        this.reason = BookSection.clean(reason);
        if (fate != Fate.NICHT_AUFGENOMMEN && this.sectionId.isEmpty()) {
            throw new IllegalArgumentException("a placement in a section needs the section id: " + this.topicId);
        }
        if (fate == Fate.NICHT_AUFGENOMMEN && this.reason.isEmpty()) {
            throw new IllegalArgumentException("a topic left out needs a reason: " + this.topicId);
        }
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        for (String id : passageIds == null ? Collections.<String>emptyList() : passageIds) {
            if (id != null && !id.trim().isEmpty()) {
                ids.add(id.trim());
            }
        }
        this.passageIds = Collections.unmodifiableList(new ArrayList<String>(ids));
    }

    /** The topic has the section of its own; {@code passageIds} are the passages the plan gave it (may be empty). */
    public static Placement ownSection(String topicId, String sectionId, List<String> passageIds) {
        return new Placement(topicId, Fate.EIGENER_ABSCHNITT, sectionId, "", passageIds);
    }

    /** The topic went into the section with other topics; {@code reason} may say why it has none of its own. */
    public static Placement mergedInto(String topicId, String sectionId, String reason, List<String> passageIds) {
        return new Placement(topicId, Fate.AUFGEGANGEN_IN, sectionId, reason, passageIds);
    }

    /** The topic is not in the book, for the reason given (required). */
    public static Placement notTaken(String topicId, String reason) {
        return new Placement(topicId, Fate.NICHT_AUFGENOMMEN, "", reason, Collections.<String>emptyList());
    }

    public String getTopicId() {
        return topicId;
    }

    public Fate getFate() {
        return fate;
    }

    /** The section the topic is in, empty when it was not taken. */
    public String getSectionId() {
        return sectionId;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getPassageIds() {
        return passageIds;
    }
}
