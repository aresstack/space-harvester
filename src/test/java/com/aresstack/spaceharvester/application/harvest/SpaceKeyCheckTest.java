package com.aresstack.spaceharvester.application.harvest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpaceKeyCheckTest {

    /** Kennt DEV und ~jane.doe, ist für DOWN nicht erreichbar und kennt sonst nichts. */
    private final List<String> listed = new ArrayList<>();
    private final HarvestSource source = new HarvestSource() {
        @Override
        public String id() {
            return "wiki";
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return List.of();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            listed.add(partitionKey + ":" + limit);
            return switch (partitionKey) {
                case "DEV", "~jane.doe" -> new PageMetadataBatch(List.of(), null);
                case "DOWN" -> throw new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE,
                        "Zeitüberschreitung", null);
                default -> throw new SourceAccessException(id(), SourceAccessException.Reason.NOT_FOUND,
                        "Space nicht gefunden", null);
            };
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            throw new UnsupportedOperationException();
        }
    };
    private final SpaceKeyCheck check = new SpaceKeyCheck(new SourceQueries(new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
        }
    }));

    @Test
    void existingGlobalAndPersonalSpacesPassWithASingleSmallListing() {
        assertThatCode(() -> check.requireExisting("wiki", "DEV")).doesNotThrowAnyException();
        assertThatCode(() -> check.requireExisting("wiki", "~jane.doe")).doesNotThrowAnyException();

        assertThat(listed).containsExactly("DEV:1", "~jane.doe:1");
    }

    @Test
    void malformedKeysAreRejectedWithoutAskingTheSource() {
        for (String key : List.of("", " ", "DE V", "../DEV", "DEV/x", "DEV?x=1", "~", "a".repeat(256))) {
            assertThatThrownBy(() -> check.requireExisting("wiki", key)).as(key)
                    .isInstanceOf(InvalidQueryException.class);
        }
        assertThatThrownBy(() -> check.requireExisting("wiki", null)).isInstanceOf(InvalidQueryException.class);
        assertThat(listed).isEmpty();
    }

    @Test
    void anUnknownSpaceIsNotFound() {
        assertThatThrownBy(() -> check.requireExisting("wiki", "NOPE"))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
    }

    @Test
    void anUnknownSourceIsReported() {
        assertThatThrownBy(() -> check.requireExisting("other", "DEV")).isInstanceOf(UnknownSourceException.class);
    }

    @Test
    void anUnreachableSourceDoesNotBlockTheJobWhichReportsTheFailureItself() {
        assertThatCode(() -> check.requireExisting("wiki", "DOWN")).doesNotThrowAnyException();
    }
}
