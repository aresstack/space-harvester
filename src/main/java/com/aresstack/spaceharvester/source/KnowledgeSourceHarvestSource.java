package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.util.List;

/** {@link HarvestSource} über einen {@link KnowledgeSourcePort} aus {@code enterprise-ai-sources}. */
final class KnowledgeSourceHarvestSource implements HarvestSource {

    private final String id;
    private final String type;
    private final String title;
    private final KnowledgeSourcePort port;

    KnowledgeSourceHarvestSource(String id, String type, String title, KnowledgeSourcePort port) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.port = port;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public List<Partition> partitions() {
        try {
            return port.partitions().stream().map(KnowledgeSourceHarvestSource::partition).toList();
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    private static Partition partition(SourcePartition partition) {
        return new Partition(partition.key(), partition.title(),
                partition.homeResource() == null ? null : partition.homeResource().value());
    }

    private SourceAccessException accessFailure(KnowledgeSourceException e) {
        SourceAccessException.Reason reason = switch (e.kind()) {
            case NOT_FOUND -> SourceAccessException.Reason.NOT_FOUND;
            case UNAVAILABLE -> SourceAccessException.Reason.UNAVAILABLE;
            case ACCESS_DENIED -> SourceAccessException.Reason.ACCESS_DENIED;
            case INVALID_RESPONSE -> SourceAccessException.Reason.INVALID_RESPONSE;
            case UNSUPPORTED -> SourceAccessException.Reason.UNSUPPORTED;
        };
        return new SourceAccessException(id, reason, e.getMessage(), e);
    }
}
