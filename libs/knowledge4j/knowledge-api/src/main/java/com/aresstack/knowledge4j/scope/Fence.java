package com.aresstack.knowledge4j.scope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Mission and Weidezaun of a work: the mission with its edge, what explicitly belongs to it, what is excluded, place
 * and time. It is the only measure of what the research collects and of what the writing makes of it; how the
 * research steers its search (a mind map, a plan) is no part of it, so a changed search plan changes no verdict
 * against the fence. The fence is the user's and no model moves it. Immutable.
 */
public final class Fence {

    private final String mission;
    private final String edge;
    private final List<String> included;
    private final List<String> excluded;
    private final String place;
    private final String time;

    public Fence(String mission, String edge, List<String> included, List<String> excluded, String place,
                         String time) {
        this.mission = clean(mission);
        this.edge = clean(edge);
        this.included = cleanAll(included);
        this.excluded = cleanAll(excluded);
        this.place = clean(place);
        this.time = clean(time);
    }

    /** Only the mission. */
    public static Fence of(String mission) {
        return new Fence(mission, "", null, null, "", "");
    }

    public String getMission() {
        return mission;
    }

    public String getEdge() {
        return edge;
    }

    public List<String> getIncluded() {
        return included;
    }

    public List<String> getExcluded() {
        return excluded;
    }

    public String getPlace() {
        return place;
    }

    public String getTime() {
        return time;
    }

    /**
     * This fence with more of what belongs to it and more of what is excluded, as the reader answers the program's
     * yes/no questions on neighbouring fields after the search; lines already in a list stay once.
     */
    public Fence widened(List<String> moreIncluded, List<String> moreExcluded) {
        return new Fence(mission, edge, joined(included, moreIncluded), joined(excluded, moreExcluded), place, time);
    }

    private static List<String> joined(List<String> lines, List<String> more) {
        List<String> all = new ArrayList<String>(lines);
        if (more != null) {
            for (String line : more) {
                String v = clean(line);
                if (!v.isEmpty() && !all.contains(v)) {
                    all.add(v);
                }
            }
        }
        return all;
    }

    /** Whether the fence says more than the mission: an edge, inclusions, exclusions, place or time. */
    public boolean hasBounds() {
        return !edge.isEmpty() || !included.isEmpty() || !excluded.isEmpty() || !place.isEmpty() || !time.isEmpty();
    }

    /** The fingerprint of everything a verdict against the fence depends on: 16 hex digits of SHA-256. */
    public String basis() {
        StringBuilder text = new StringBuilder();
        text.append("auftrag\n").append(mission).append("\nrand\n").append(edge);
        text.append("\ndazu\n").append(String.join("\n", included));
        text.append("\nnicht\n").append(String.join("\n", excluded));
        text.append("\nraum\n").append(place).append("\nzeit\n").append(time);
        return hash(text.toString());
    }

    /**
     * The fence as a prompt shows it: the mission (cut to {@code missionChars}), the edge, what belongs to it, what is
     * excluded, place and time; each part only when it is set.
     */
    public String describe(boolean german, int missionChars) {
        StringBuilder text = new StringBuilder();
        String shown = mission.isEmpty() ? (german ? "(keiner angegeben)" : "(none given)") : cut(mission, missionChars);
        text.append(german ? "Buchauftrag:\n" : "Book mission:\n").append(shown).append("\n\n");
        if (!edge.isEmpty()) {
            text.append(german ? "Rand des Themas:\n" : "Edge of the subject:\n").append(edge).append("\n\n");
        }
        list(text, german ? "Dazu gehört ausdrücklich:" : "Explicitly included:", included);
        list(text, german ? "Ausgeschlossen:" : "Excluded:", excluded);
        if (!place.isEmpty()) {
            text.append(german ? "Räumlicher Rahmen: " : "Place: ").append(place).append('\n');
        }
        if (!time.isEmpty()) {
            text.append(german ? "Zeitlicher Rahmen: " : "Time: ").append(time).append('\n');
        }
        if (!place.isEmpty() || !time.isEmpty()) {
            text.append('\n');
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return "Fence{" + basis() + "}";
    }

    private static void list(StringBuilder text, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        text.append(title).append('\n');
        for (String line : lines) {
            text.append("- ").append(line).append('\n');
        }
        text.append('\n');
    }

    /** At most {@code chars} characters, cut at the last blank when it is not too far back, then " …". */
    public static String cut(String text, int chars) {
        if (text.length() <= chars) {
            return text;
        }
        String head = text.substring(0, chars);
        int blank = head.lastIndexOf(' ');
        return (blank >= chars * 3 / 5 ? head.substring(0, blank) : head).trim() + " …";
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 missing", missing);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> cleanAll(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                String v = clean(value);
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }
}
