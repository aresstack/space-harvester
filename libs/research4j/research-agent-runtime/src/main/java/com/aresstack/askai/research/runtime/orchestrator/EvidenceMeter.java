package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskState;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measures a run's evidence from the BLACKBOARD alone: the {@code SOURCE_REFERENCE} artifacts its tasks
 * recorded (project ref = the accepted source id, content = the page URL, optionally followed by a space and the
 * page title) and the sections each task serves. A source a verifier package rejected
 * ({@link WorkerArtifacts.Verdict#isRejected()}) does not count.
 * Pure code, no model: the same tasks always give the same snapshot.
 *
 * <p>A source counts for every section of the task that accepted it; a duplicate acceptance (the same source
 * id from two tasks) counts once per section and once overall. Hosts are the URL hosts without a leading
 * {@code www.}, with an explicit port kept.</p>
 */
public final class EvidenceMeter {

    private final int minimumSources;
    private final int minimumHosts;
    private final int minimumSourcesPerSection;

    /**
     * @param minimumSources           the run rule's source count (the search-run settings, today 3)
     * @param minimumHosts             the run rule's host count (today 2)
     * @param minimumSourcesPerSection the coverage rule per outline section (a setting)
     */
    public EvidenceMeter(int minimumSources, int minimumHosts, int minimumSourcesPerSection) {
        this.minimumSources = Math.max(0, minimumSources);
        this.minimumHosts = Math.max(0, minimumHosts);
        this.minimumSourcesPerSection = Math.max(0, minimumSourcesPerSection);
    }

    public EvidenceSnapshot measure(OrchestratorRun run, List<ResearchTask> tasks) {
        ResearchOutline outline = run.outline();
        Map<String, Set<String>> sourcesBySection = new HashMap<String, Set<String>>();
        Map<String, Set<String>> hostsBySection = new HashMap<String, Set<String>>();
        Map<String, Integer> open = new HashMap<String, Integer>();
        Map<String, Integer> failed = new HashMap<String, Integer>();
        Set<String> allSources = new HashSet<String>();
        Set<String> allHosts = new HashSet<String>();
        int openQuestions = 0;
        // Lane G: a source the verifier rejected (off-topic, unreliable) is no evidence, wherever it came from.
        java.util.List<ResearchTask> owned = new java.util.ArrayList<ResearchTask>();
        for (ResearchTask task : tasks) {
            if (run.owns(task.getTaskId())) {
                owned.add(task);
            }
        }
        Set<String> rejected = WorkerArtifacts.rejectedSources(owned);
        for (ResearchTask task : tasks) {
            if (!run.owns(task.getTaskId())) {
                continue;
            }
            List<String> sections = TaskBriefs.sectionsOf(task.getInputs());
            if (task.getState() == TaskState.INPUT_REQUIRED) {
                openQuestions++;
            }
            for (String section : sections) {
                if (!task.isTerminal()) {
                    increment(open, section);
                } else if (task.getState() != TaskState.COMPLETED) {
                    increment(failed, section);
                }
            }
            for (TaskArtifact artifact : task.getArtifacts()) {
                if (artifact.getKind() != TaskArtifact.Kind.SOURCE_REFERENCE) {
                    continue;
                }
                String sourceId = artifact.getProjectRef().isEmpty() ? artifact.getContent()
                        : artifact.getProjectRef();
                if (rejected.contains(sourceId)) {
                    continue;
                }
                String host = hostOf(artifact.getContent());
                allSources.add(sourceId);
                if (!host.isEmpty()) {
                    allHosts.add(host);
                }
                for (String section : sections) {
                    add(sourcesBySection, section, sourceId);
                    if (!host.isEmpty()) {
                        add(hostsBySection, section, host);
                    }
                }
            }
        }
        java.util.List<EvidenceSnapshot.SectionCoverage> coverage =
                new java.util.ArrayList<EvidenceSnapshot.SectionCoverage>();
        for (ResearchOutline.Section section : outline.getSections()) {
            coverage.add(new EvidenceSnapshot.SectionCoverage(section.getId(), section.getTitle(),
                    size(sourcesBySection, section.getId()), size(hostsBySection, section.getId()),
                    count(open, section.getId()), count(failed, section.getId())));
        }
        return new EvidenceSnapshot(coverage, allSources.size(), allHosts.size(), minimumSources, minimumHosts,
                minimumSourcesPerSection, openQuestions);
    }

    static String hostOf(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        String first = url.trim().split("\\s+", 2)[0];
        try {
            URI uri = new URI(first);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase(Locale.ROOT);
            host = host.startsWith("www.") ? host.substring(4) : host;
            // An explicit port is part of the host: loopback fixture worlds serve every "site" on its own port.
            return uri.getPort() < 0 ? host : host + ":" + uri.getPort();
        } catch (Exception notAUrl) {
            return "";
        }
    }

    private static void add(Map<String, Set<String>> map, String key, String value) {
        Set<String> values = map.get(key);
        if (values == null) {
            values = new HashSet<String>();
            map.put(key, values);
        }
        values.add(value);
    }

    private static int size(Map<String, Set<String>> map, String key) {
        Set<String> values = map.get(key);
        return values == null ? 0 : values.size();
    }

    private static void increment(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        map.put(key, value == null ? 1 : value + 1);
    }

    private static int count(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }
}
