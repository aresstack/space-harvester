package com.aresstack.askai.research.runtime.orchestrator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The sections a research run must cover: what the user approved, read from the host's concept (the
 * {@code concept_read} tool's branch JSON: one object with exactly one array-valued property; strings are
 * leaves, objects with one array-valued property are branches). The first level of the concept is the
 * section list. The orchestrator plans AGAINST these sections and the {@link EvidenceMeter} measures coverage
 * per section, so the gap check never rests on a list the model invented.
 *
 * <p>A concept without children (or no concept at all) yields one section, {@link #QUESTION_SECTION}: the
 * research question as a whole.</p>
 */
public final class ResearchOutline {

    /** The id of the one section of an outline without a concept. */
    public static final String QUESTION_SECTION = "question";

    private final String question;
    private final List<Section> sections;

    public ResearchOutline(String question, List<Section> sections) {
        this.question = question == null ? "" : question.trim();
        List<Section> copy = new ArrayList<Section>();
        Set<String> ids = new LinkedHashSet<String>();
        if (sections != null) {
            for (Section section : sections) {
                if (section != null && ids.add(section.getId())) {
                    copy.add(section);
                }
            }
        }
        if (copy.isEmpty()) {
            copy.add(new Section(QUESTION_SECTION, this.question.isEmpty() ? "research question" : this.question));
        }
        this.sections = Collections.unmodifiableList(copy);
    }

    /** An outline of the question alone. */
    public static ResearchOutline ofQuestion(String question) {
        return new ResearchOutline(question, null);
    }

    /**
     * The outline from a {@code concept_read} answer: the leading {@code key=value} lines (revision, parent,
     * siblings) are skipped up to the first {@code '{'}; a trailing note after the JSON is ignored. A concept
     * that does not parse yields the question-only outline: the run still works, only coarser.
     */
    public static ResearchOutline fromConceptRead(String question, String conceptReadAnswer) {
        if (conceptReadAnswer == null) {
            return ofQuestion(question);
        }
        int start = conceptReadAnswer.indexOf('{');
        int end = conceptReadAnswer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return ofQuestion(question);
        }
        try {
            JsonElement root = JsonParser.parseString(conceptReadAnswer.substring(start, end + 1));
            JsonArray children = childrenOf(root);
            List<Section> sections = new ArrayList<Section>();
            Set<String> ids = new LinkedHashSet<String>();
            if (children != null) {
                for (JsonElement child : children) {
                    String title = nameOf(child);
                    if (title.isEmpty()) {
                        continue;
                    }
                    String id = uniqueId(slug(title), ids);
                    ids.add(id);
                    sections.add(new Section(id, title));
                }
            }
            return new ResearchOutline(question, sections);
        } catch (RuntimeException unreadable) {
            return ofQuestion(question);
        }
    }

    public String getQuestion() {
        return question;
    }

    /** Never empty. */
    public List<Section> getSections() {
        return sections;
    }

    public List<String> sectionIds() {
        List<String> ids = new ArrayList<String>();
        for (Section section : sections) {
            ids.add(section.getId());
        }
        return ids;
    }

    public boolean hasSection(String id) {
        for (Section section : sections) {
            if (section.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** The section's title, or the id itself for an unknown id. */
    public String titleOf(String id) {
        for (Section section : sections) {
            if (section.getId().equals(id)) {
                return section.getTitle();
            }
        }
        return id;
    }

    private static JsonArray childrenOf(JsonElement node) {
        if (node == null || !node.isJsonObject()) {
            return null;
        }
        JsonObject object = node.getAsJsonObject();
        for (String key : object.keySet()) {
            if (object.get(key).isJsonArray()) {
                return object.get(key).getAsJsonArray();
            }
        }
        return null;
    }

    private static String nameOf(JsonElement child) {
        if (child.isJsonPrimitive()) {
            return child.getAsString().trim();
        }
        if (child.isJsonObject()) {
            JsonObject object = child.getAsJsonObject();
            for (String key : object.keySet()) {
                if (object.get(key).isJsonArray()) {
                    return key.trim();
                }
            }
        }
        return "";
    }

    static String slug(String title) {
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (char c : title.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                dash = false;
            } else if (!dash && out.length() > 0) {
                out.append('-');
                dash = true;
            }
        }
        String slug = out.toString();
        while (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        if (slug.length() > 48) {
            slug = slug.substring(0, 48);
        }
        return slug.isEmpty() ? "section" : slug;
    }

    private static String uniqueId(String base, Set<String> taken) {
        if (!taken.contains(base) && !QUESTION_SECTION.equals(base)) {
            return base;
        }
        int n = 2;
        while (taken.contains(base + "-" + n)) {
            n++;
        }
        return base + "-" + n;
    }

    /** One section: a stable id (a slug of the title) and the title the user sees. */
    public static final class Section {
        private final String id;
        private final String title;

        public Section(String id, String title) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("section id must not be empty");
            }
            this.id = id.trim();
            this.title = title == null || title.trim().isEmpty() ? this.id : title.trim();
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }
    }
}
