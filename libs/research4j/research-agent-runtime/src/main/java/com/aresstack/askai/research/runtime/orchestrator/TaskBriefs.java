package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.TaskInputs;

import java.util.ArrayList;
import java.util.List;

/**
 * How a {@link PlannedTask} is written into the domain's {@link TaskInputs} and read back. The domain task
 * carries a brief and artifact references only (the project store stays the blackboard), so the orchestrated
 * run uses two conventions: the brief starts with one {@code query: } line, and the outline sections the task
 * serves are references of the form {@code section:<id>}.
 */
public final class TaskBriefs {

    static final String QUERY_PREFIX = "query: ";
    static final String SECTION_PREFIX = "section:";

    private TaskBriefs() {
    }

    public static TaskInputs inputsOf(PlannedTask task) {
        StringBuilder brief = new StringBuilder();
        if (!task.getQuery().isEmpty()) {
            brief.append(QUERY_PREFIX).append(task.getQuery().replace('\n', ' ')).append('\n');
        }
        brief.append(task.getBrief());
        List<String> refs = new ArrayList<String>();
        for (String section : task.getSections()) {
            refs.add(SECTION_PREFIX + section);
        }
        return new TaskInputs(brief.toString(), refs);
    }

    /** The query of a task's brief, or the whole brief when it has no query line. */
    public static String queryOf(TaskInputs inputs) {
        String brief = inputs.getBrief();
        if (brief.startsWith(QUERY_PREFIX)) {
            int end = brief.indexOf('\n');
            return (end < 0 ? brief.substring(QUERY_PREFIX.length())
                    : brief.substring(QUERY_PREFIX.length(), end)).trim();
        }
        return brief.trim();
    }

    /** The brief without the query line. */
    public static String briefOf(TaskInputs inputs) {
        String brief = inputs.getBrief();
        if (brief.startsWith(QUERY_PREFIX)) {
            int end = brief.indexOf('\n');
            return end < 0 ? "" : brief.substring(end + 1).trim();
        }
        return brief.trim();
    }

    /** The outline sections a task serves. */
    public static List<String> sectionsOf(TaskInputs inputs) {
        List<String> sections = new ArrayList<String>();
        for (String ref : inputs.getArtifactRefs()) {
            if (ref.startsWith(SECTION_PREFIX) && ref.length() > SECTION_PREFIX.length()) {
                sections.add(ref.substring(SECTION_PREFIX.length()));
            }
        }
        return sections;
    }
}
