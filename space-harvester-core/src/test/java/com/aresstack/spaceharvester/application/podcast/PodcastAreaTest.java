package com.aresstack.spaceharvester.application.podcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PodcastAreaTest {

    @TempDir
    Path directory;

    @Test
    void theKeyOfAnUpdatePodcastStaysAsBeforeTheAreaKey() throws Exception {
        // Bisherige Bildung: Verfahren, Quelle, Update, Space, Zeitpunkt, Text.
        String before = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((UpdatePodcasts.VERSION
                + "\nwiki\nu1\nDEV\n2026-11-23T10:00:00Z\nText").getBytes(StandardCharsets.UTF_8)), 0, 16);

        assertThat(UpdatePodcasts.key("wiki", "u1", "DEV", "Text", "2026-11-23T10:00:00Z")).isEqualTo(before);
        assertThat(UpdatePodcasts.key(PodcastArea.update("wiki", "u1", "DEV"), "Text", "2026-11-23T10:00:00Z"))
                .isEqualTo(before);
        assertThat(UpdatePodcasts.key(new PodcastArea("selection-abc", null, null, null), "Text",
                "2026-11-23T10:00:00Z")).isNotEqualTo(before);
    }

    @Test
    void anOldUpdatePodcastWithoutAreaGetsItsAreaFromSourceUpdateAndSpace() throws Exception {
        PodcastStore store = new PodcastStore(directory);
        Files.createDirectories(directory.resolve("0123456789abcdef"));
        Files.writeString(directory.resolve("0123456789abcdef").resolve("podcast.json"),
                "{\"sourceId\":\"wiki\",\"updateId\":\"u1\",\"spaceKey\":null,\"pages\":[\"1\"]}");

        assertThat(store.meta("0123456789abcdef").orElseThrow().area())
                .isEqualTo(PodcastArea.update("wiki", "u1", null).key());
    }

    @Test
    void aPodcastOfASelectionAreaNeedsNoSingleSourceAndKeepsItsArea() {
        PodcastStore store = new PodcastStore(directory);
        PodcastArea area = new PodcastArea("selection-abc", null, null, null);
        store.saveMeta(new PodcastStore.Meta("fedcba9876543210", area, List.of("1", "2")));

        PodcastStore.Meta meta = store.meta("fedcba9876543210").orElseThrow();
        assertThat(meta.area()).isEqualTo("selection-abc");
        assertThat(meta.sourceId()).isNull();
        assertThat(meta.pages()).containsExactly("1", "2");
    }
}
