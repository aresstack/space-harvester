package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThematicSummarizerTest {

    @Test
    void aliasesStayThePageIdWithinOneSourceAndCarryTheSourceOnlyWhereIdsCollide() {
        PageKey wiki100 = new PageKey("wiki", "100");
        PageKey wiki200 = new PageKey("wiki", "200");
        PageKey jira100 = new PageKey("jira", "100");

        assertThat(ThematicSummarizer.aliases(List.of(wiki100, wiki200)))
                .containsEntry(wiki100, "100").containsEntry(wiki200, "200");
        assertThat(ThematicSummarizer.aliases(List.of(wiki100, wiki200, jira100)))
                .containsEntry(wiki100, "wiki:100").containsEntry(jira100, "jira:100").containsEntry(wiki200, "200");
    }

    @Test
    void anInputPageWithoutSourceBelongsToTheSourceOfItsSummary() {
        assertThat(new StoredSummary.InputPage("DEV", "1", true).sourceIdOr("wiki")).isEqualTo("wiki");
        assertThat(new StoredSummary.InputPage("jira", "DEV", "1", true).sourceIdOr("wiki")).isEqualTo("jira");
    }
}
