package com.aresstack.spaceharvester.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Vertragstest für jede {@link SnapshotRepository}-Umsetzung. Eine Umsetzung erbt davon und liefert ein leeres
 * Repository; persistente Umsetzungen überschreiben zusätzlich {@link #reopen} für den Neustart.
 */
public abstract class SnapshotRepositoryContract {

    /** Ein neues, leeres Repository. */
    protected abstract SnapshotRepository emptyRepository();

    /**
     * Dasselbe Repository nach einem Neustart (schließen und neu öffnen). Nicht persistente Umsetzungen geben es
     * unverändert zurück.
     */
    protected SnapshotRepository reopen(SnapshotRepository repository) {
        return repository;
    }

    /** Bytes, die kein gültiges UTF-8 sind: eine Umsetzung darf die Payload nicht als Text behandeln. */
    static final byte[] NOT_UTF8 = {'{', 0x00, (byte) 0xC3, (byte) 0x28, (byte) 0xFF, '}'};

    static PageSnapshot snapshot(String sourceId, String pageId, String version, byte[] payload) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId,
                "Seite " + pageId, "DEV", "100", version, Instant.parse("2026-10-15T10:30:00Z"), null,
                PageMetadata.sorted(Map.of("confluence.labels", "api")));
        return PageSnapshot.of(sourceId, metadata,
                new NativePayload(payload, "application/json", "confluence-rest-content+json;expand=body.storage"),
                Instant.parse("2026-10-16T20:00:00Z"));
    }

    static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void returnsThePayloadByteForByteWithItsHeader() {
        SnapshotRepository repository = emptyRepository();
        PageSnapshot snapshot = snapshot("wiki", "101", "3", NOT_UTF8);

        repository.save(snapshot);

        PageSnapshot found = repository.find(snapshot.key()).orElseThrow();
        assertThat(found.payload().bytes()).isEqualTo(NOT_UTF8);
        assertThat(found.payload().rawSha256()).isEqualTo(snapshot.payload().rawSha256());
        assertThat(found).isEqualTo(snapshot);
    }

    @Test
    void unknownPageIsEmpty() {
        assertThat(emptyRepository().find(new PageKey("wiki", "999"))).isEmpty();
    }

    @Test
    void lastSavedStateWinsAndSavingTheSameStateAgainChangesNothing() {
        SnapshotRepository repository = emptyRepository();
        PageSnapshot first = snapshot("wiki", "101", "3", json("{\"v\":3}"));
        PageSnapshot second = snapshot("wiki", "101", "4", json("{\"v\":4}"));

        repository.save(first);
        repository.save(second);
        repository.save(second);

        assertThat(repository.find(first.key())).contains(second);
    }

    @Test
    void sameIdInTwoSourcesAreTwoPages() {
        SnapshotRepository repository = emptyRepository();
        PageSnapshot wiki = snapshot("wiki", "101", "3", json("{\"q\":\"wiki\"}"));
        PageSnapshot archive = snapshot("archive", "101", "9", json("{\"q\":\"archive\"}"));

        repository.save(wiki);
        repository.save(archive);

        assertThat(repository.find(wiki.key())).contains(wiki);
        assertThat(repository.find(archive.key())).contains(archive);
    }

    @Test
    void removeDeletesOnlyThatPage() {
        SnapshotRepository repository = emptyRepository();
        PageSnapshot kept = snapshot("wiki", "101", "3", json("{}"));
        PageSnapshot removed = snapshot("wiki", "102", "1", json("{}"));
        repository.save(kept);
        repository.save(removed);

        assertThat(repository.remove(removed.key())).isTrue();
        assertThat(repository.remove(removed.key())).isFalse();

        assertThat(repository.find(removed.key())).isEmpty();
        assertThat(repository.find(kept.key())).contains(kept);
    }

    @Test
    void savedStatesSurviveARestart() {
        SnapshotRepository repository = emptyRepository();
        PageSnapshot snapshot = snapshot("wiki", "101", "3", NOT_UTF8);
        repository.save(snapshot);

        assertThat(reopen(repository).find(snapshot.key())).contains(snapshot);
    }
}
