package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.NavigationResourceSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A refused login during navigation-driven acquisition must surface instead of looking like "no page". */
class NavigationResourceFailureTest {

    @Test
    void onlyAMissingObjectIsNoPageARefusedLoginIsAFailure() {
        assertThat(source(KnowledgeSourceException.Kind.NOT_FOUND).navigationPageId("'APP.COBOL(X)'")).isEmpty();
        assertThatThrownBy(() -> source(KnowledgeSourceException.Kind.ACCESS_DENIED).navigationPageId("'APP.COBOL(X)'"))
                .isInstanceOf(SourceAccessException.class);
        assertThatThrownBy(() -> source(KnowledgeSourceException.Kind.UNAVAILABLE).navigationPageId("'APP.COBOL(X)'"))
                .isInstanceOf(SourceAccessException.class);
    }

    private static KnowledgeSourceHarvestSource source(KnowledgeSourceException.Kind failure) {
        return new KnowledgeSourceHarvestSource("ftp-test", "ftp", "FTP", new Failing(failure));
    }

    private record Failing(KnowledgeSourceException.Kind failure) implements KnowledgeSourcePort, NavigationResourceSource {
        @Override public SourceAddress sourceId() { return SourceAddress.of("ftp-test"); }
        @Override public List<KnowledgeResource> discover(SourceScope scope) { return List.of(); }
        @Override public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
            throw new KnowledgeSourceException(failure, "test");
        }
        @Override public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) { return List.of(); }
        @Override public KnowledgeResourceId navigationResource(String nativePath) throws KnowledgeSourceException {
            throw new KnowledgeSourceException(failure, "login refused");
        }
    }
}
