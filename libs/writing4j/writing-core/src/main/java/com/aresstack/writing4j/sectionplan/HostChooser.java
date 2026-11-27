package com.aresstack.writing4j.sectionplan;

import java.util.Collections;
import java.util.List;

/**
 * Picks the topic of the outline in whose section a topic without a section of its own fits best (component 9). The
 * plan asks it only after shared evidence found no place; a failed choice falls back to shared words, so a topic with
 * evidence is never lost because a model failed.
 */
public interface HostChooser {

    /** A topic as the chooser sees it: id, name, description and, for a candidate, the chapter it stands in. */
    final class Option {
        private final String topicId;
        private final String name;
        private final String description;
        private final String chapterTitle;

        public Option(String topicId, String name, String description, String chapterTitle) {
            this.topicId = SectionPlan.clean(topicId);
            this.name = SectionPlan.clean(name);
            this.description = SectionPlan.clean(description);
            this.chapterTitle = SectionPlan.clean(chapterTitle);
        }

        public String getTopicId() {
            return topicId;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getChapterTitle() {
            return chapterTitle;
        }
    }

    /** The answer: a candidate's topic id, none, or a failure. */
    final class Choice {
        private final String topicId;
        private final boolean failed;
        private final String reason;
        private final List<String> answers;

        private Choice(String topicId, boolean failed, String reason, List<String> answers) {
            this.topicId = SectionPlan.clean(topicId);
            this.failed = failed;
            this.reason = SectionPlan.clean(reason);
            this.answers = answers == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(answers);
        }

        /** The topic fits in the section of {@code topicId}. */
        public static Choice of(String topicId, String reason, List<String> answers) {
            return new Choice(topicId, false, reason, answers);
        }

        /** The topic fits in none of the candidates. */
        public static Choice none(String reason, List<String> answers) {
            return new Choice("", false, reason, answers);
        }

        /** No usable answer; {@code why} says what went wrong. */
        public static Choice failed(String why, List<String> answers) {
            return new Choice("", true, why, answers);
        }

        /** The chosen topic, empty for none or a failure. */
        public String getTopicId() {
            return topicId;
        }

        public boolean isFailed() {
            return failed;
        }

        public String getReason() {
            return reason;
        }

        /** The raw answers, one per attempt, for the stock test's files. */
        public List<String> getAnswers() {
            return answers;
        }
    }

    Choice choose(Option topic, List<Option> candidates);

    /** What answers, for the plan's record. */
    String modelName();
}
