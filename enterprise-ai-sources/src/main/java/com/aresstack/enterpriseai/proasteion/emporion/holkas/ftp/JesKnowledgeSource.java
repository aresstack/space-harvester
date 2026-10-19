package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JES-Jobausgaben (JCL, Systemmeldungen, SYSPRINT) eines z/OS-FTP-Servers als {@link KnowledgeSourcePort}.
 *
 * <ul>
 *   <li>Startpunkte: Jobnamen-Filter ({@code *}, {@code PAY*}); Besitzer und Status kommen aus der Konfiguration
 *       (Standard: angemeldeter Benutzer, nur fertige Jobs {@code OUTPUT}).</li>
 *   <li>IDs: {@code jes:<Quell-ID>/<Job-ID>} ({@code JOB12345}).</li>
 *   <li>Inhalt: gesamte Ausgabe ({@code <Job-ID>.x}) als Codeblock; Revision = Job-ID (die Ausgabe
 *       eines fertigen Jobs ändert sich nicht).</li>
 * </ul>
 */
final class JesKnowledgeSource implements KnowledgeSourcePort {

    static final String SCHEME = "jes";

    private static final Pattern JOB_CARD = Pattern.compile("^\\s*\\d*\\s*//(\\S+)\\s+JOB\\b", Pattern.MULTILINE);

    private final SourceAddress sourceId;
    /** {@code host:port} klein, Teil jeder Ressourcen-ID ({@code jes:host:port/JOB01234}). */
    private final String locator;
    private final String ownerFilter;
    private final String statusFilter;
    private final FtpSessionPool<JesFtpSession> sessions;

    JesKnowledgeSource(SourceAddress sourceId, String locator, String ownerFilter, String statusFilter,
                       FtpSessionPool<JesFtpSession> sessions) {
        if (sourceId == null || locator == null || statusFilter == null || sessions == null) {
            throw new IllegalArgumentException("sourceId, locator, statusFilter and sessions are required");
        }
        this.sourceId = sourceId;
        this.locator = locator;
        this.ownerFilter = ownerFilter;
        this.statusFilter = statusFilter;
        this.sessions = sessions;
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public List<KnowledgeResource> discover(final SourceScope scope) throws KnowledgeSourceException {
        return sessions.run(session -> {
            Map<String, KnowledgeResource> found = new LinkedHashMap<String, KnowledgeResource>();
            for (String startPoint : scope.startPoints()) {
                String filter = startPoint.trim().toUpperCase(Locale.ROOT);
                for (JesFtpSession.JesJob job : session.listJobs(ownerFilter, filter.isEmpty() ? "*" : filter,
                        statusFilter)) {
                    if (found.size() >= scope.maxResources()) {
                        return new ArrayList<KnowledgeResource>(found.values());
                    }
                    if (!found.containsKey(job.jobId)) {
                        found.put(job.jobId, resource(job.jobId, job.jobName, job.owner,
                                KnowledgeRevision.version(job.jobId)));
                    }
                }
            }
            return new ArrayList<KnowledgeResource>(found.values());
        });
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        final String jobId = jobIdOf(resourceId);
        return sessions.run(session -> {
            String output = session.readOutput(jobId);
            Matcher card = JOB_CARD.matcher(output);
            String jobName = card.find() ? card.group(1) : null;
            KnowledgeResource resource = resource(jobId, jobName, null,
                    KnowledgeRevision.version(jobId));
            return KnowledgeDocument.of(resource, "# " + resource.title() + "\n\n```\n" + output + "\n```");
        });
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        jobIdOf(resourceId);
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "JesKnowledgeSource{" + sourceId + "}";
    }

    private KnowledgeResource resource(String jobId, String jobName, String owner, KnowledgeRevision revision) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("jes.jobId", jobId);
        if (jobName != null) {
            metadata.put("jes.jobName", jobName);
        }
        if (owner != null) {
            metadata.put("jes.owner", owner);
        }
        return KnowledgeResource.builder(KnowledgeResourceId.of(SCHEME, locator + "/" + jobId), sourceId)
                .title(jobName == null ? jobId : jobName + " (" + jobId + ")")
                .contentType(KnowledgeResource.DEFAULT_CONTENT_TYPE)
                .revision(revision)
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private String jobIdOf(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId == null ? "" : resourceId.value();
        String jobId = value.startsWith(prefix) ? value.substring(prefix.length()) : "";
        if (jobId.isEmpty() || jobId.contains("/") || jobId.contains(" ")) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        return jobId;
    }
}
