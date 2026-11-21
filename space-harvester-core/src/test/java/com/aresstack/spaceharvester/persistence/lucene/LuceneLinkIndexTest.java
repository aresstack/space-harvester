package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.links.LinkIndex;
import com.aresstack.spaceharvester.application.links.LinkTarget;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Das Verzeichnis der Verweise bleibt über einen Neustart erhalten (Paket V1). */
class LuceneLinkIndexTest {

    @TempDir
    Path directory;

    @Test
    void entriesSurviveReopeningAndRemovalsStayRemoved() {
        PageKey start = new PageKey("wiki", "1");
        PageKey plan = new PageKey("wiki", "2");
        try (LuceneLinkIndex index = LuceneLinkIndex.open(directory)) {
            index.put(new LinkIndex.Entry(start, "DEV", "Start", "sha256:a",
                    List.of(LinkTarget.page("2"), LinkTarget.titled("OPS", "Betrieb & Wartung"))));
            index.put(new LinkIndex.Entry(plan, "DEV", "Plan", "sha256:b", List.of()));
            index.remove(plan);
        }

        try (LuceneLinkIndex index = LuceneLinkIndex.open(directory)) {
            assertThat(index.pageIds("wiki")).containsExactly("1");
            assertThat(index.entry(start)).hasValueSatisfying(entry -> {
                assertThat(entry.rawSha256()).isEqualTo("sha256:a");
                assertThat(entry.targets()).containsExactly(LinkTarget.page("2"),
                        LinkTarget.titled("OPS", "Betrieb & Wartung"));
            });
            assertThat(index.linkingToId("wiki", "2")).extracting(LinkIndex.Entry::page).containsExactly(start);
            assertThat(index.linkingToTitle("wiki", LinkTarget.titleKey("OPS", "betrieb & wartung")))
                    .extracting(LinkIndex.Entry::page).containsExactly(start);
            assertThat(index.titled("wiki", LinkTarget.titleKey("DEV", "Start"))).hasSize(1);
            assertThat(index.count("wiki")).isEqualTo(1);
        }
    }
}
