package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.facts.FactEdit;
import com.aresstack.spaceharvester.application.facts.FactEditStore;
import com.aresstack.spaceharvester.application.facts.FactOrigin;
import com.aresstack.spaceharvester.application.facts.FactReview;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FileFactEditStore}: übersteht einen Neustart, Protokoll nur angehängt, unlesbare Ablage bricht ab. */
class FileFactEditStoreTest {

    private static final Instant AT = Instant.parse("2026-10-19T03:00:00Z");
    private static final PageKey PAGE = new PageKey("wiki", "p1");

    @TempDir
    Path root;

    private static FactEdit edit(String id, FactOrigin origin, FactReview review, long revision) {
        MetadataFact fact = new MetadataFact(id, PAGE, "PROPERTY", null, "{\"value\":\"Team A\"}",
                List.of(new MetadataFact.Argument("VALUE", 0, "team a")),
                new MetadataFact.Provenance("manual", "", "sha256:abc", "7", AT),
                new MetadataFact.Anchor("Betrieb", 3, null), origin, review);
        return new FactEdit(fact, revision, "alice", AT, review == FactReview.UNREVIEWED ? null : "bob",
                review == FactReview.UNREVIEWED ? null : AT);
    }

    @Test
    void entriesAndHistorySurviveARestartAndTheDirectoryAppearsOnlyOnWrite() {
        Path directory = root.resolve("facts");
        FileFactEditStore store = new FileFactEditStore(directory);
        assertThat(Files.exists(directory)).isFalse();
        FactEdit manual = edit("a".repeat(32), FactOrigin.MANUAL, FactReview.UNREVIEWED, 1);
        FactEdit reviewed = edit("b".repeat(32), FactOrigin.AUTOMATIC, FactReview.CONFIRMED, 2);

        store.write(List.of(manual, reviewed), List.of(), List.of(
                new FactEditStore.AuditEntry(manual.id(), PAGE, "add", "alice", AT, FactReview.UNREVIEWED, 1),
                new FactEditStore.AuditEntry(reviewed.id(), PAGE, "review", "bob", AT, FactReview.CONFIRMED, 2)));
        store.write(List.of(), List.of(manual.id()), List.of(
                new FactEditStore.AuditEntry(manual.id(), PAGE, "remove", "alice", AT, FactReview.UNREVIEWED, 1)));

        FileFactEditStore reopened = new FileFactEditStore(directory);
        assertThat(reopened.all()).containsExactly(reviewed);
        assertThat(reopened.forPage(PAGE)).containsExactly(reviewed);
        assertThat(reopened.history(manual.id())).extracting(FactEditStore.AuditEntry::action)
                .containsExactly("add", "remove");
        assertThat(reopened.history(manual.id()).get(0).page()).isEqualTo(PAGE);
    }

    @Test
    void aBrokenAuditLineIsSkippedButABrokenFileStopsTheStart() throws Exception {
        Path directory = root.resolve("facts");
        FileFactEditStore store = new FileFactEditStore(directory);
        FactEdit manual = edit("c".repeat(32), FactOrigin.MANUAL, FactReview.REJECTED, 1);
        store.write(List.of(manual), List.of(), List.of(
                new FactEditStore.AuditEntry(manual.id(), PAGE, "add", "alice", AT, FactReview.REJECTED, 1)));
        Files.writeString(directory.resolve(FileFactEditStore.AUDIT), "{\"factId\":\"" + manual.id() + "\",",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertThat(new FileFactEditStore(directory).history(manual.id())).hasSize(1);

        Files.writeString(directory.resolve(FileFactEditStore.REVIEWS), "{\"schema\":\"facts/v0\"}");
        assertThatThrownBy(() -> new FileFactEditStore(directory)).isInstanceOf(IllegalStateException.class);
    }
}
