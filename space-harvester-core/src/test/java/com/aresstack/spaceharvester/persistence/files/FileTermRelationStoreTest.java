package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelationStore.AuditEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Beziehungen überstehen einen Neustart; das Protokoll wird nur angehängt. */
class FileTermRelationStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");

    @TempDir
    Path directory;

    private static TermRelation relation(String from, String to, ReviewStatus status, RelationScope scope) {
        String id = TermRelation.idOf(TermRelation.key(from, to, RelationType.ABBREVIATION, scope));
        return new TermRelation(id, from, to, RelationType.ABBREVIATION, scope, RelationOrigin.RULE, status,
                List.of(new RelationEvidence("wiki", "42", 3, 6, "sha256:abc")), "Notiz", "g1", true, "rule",
                T0, status == ReviewStatus.PROPOSED ? null : "USER:angelo", status == ReviewStatus.PROPOSED ? null
                : T0.plusSeconds(5), 2);
    }

    @Test
    void relationsAndHistorySurviveReopening() {
        TermRelation rejected = relation("RSL", "Rückmeldeschlüssel", ReviewStatus.REJECTED, RelationScope.GLOBAL);
        TermRelation proposed = relation("KPI", "Kennzahl", ReviewStatus.PROPOSED, new RelationScope("wiki", "DEV"));
        FileTermRelationStore store = new FileTermRelationStore(directory);
        store.write(List.of(rejected, proposed), List.of(), List.of(
                new AuditEntry(rejected.id(), "reject", "USER:angelo", T0, ReviewStatus.REJECTED, 2)));
        store.write(List.of(), List.of(proposed.id()), List.of(
                new AuditEntry(proposed.id(), "withdraw", "rule", T0, null, 3)));

        FileTermRelationStore reopened = new FileTermRelationStore(directory);

        assertThat(reopened.all()).containsExactly(rejected);
        assertThat(reopened.find(proposed.id())).isEmpty();
        assertThat(reopened.history(rejected.id())).containsExactly(
                new AuditEntry(rejected.id(), "reject", "USER:angelo", T0, ReviewStatus.REJECTED, 2));
        assertThat(reopened.history(proposed.id())).extracting(AuditEntry::status).containsExactly((ReviewStatus) null);
    }

    @Test
    void scopedRelationRoundTripsWithoutReviewer() {
        TermRelation proposed = relation("KPI", "Kennzahl", ReviewStatus.PROPOSED, new RelationScope("wiki", "DEV"));
        new FileTermRelationStore(directory).write(List.of(proposed), List.of(), List.of());

        assertThat(new FileTermRelationStore(directory).all()).containsExactly(proposed);
    }

    @Test
    void brokenAuditLineIsSkipped() throws Exception {
        TermRelation rejected = relation("RSL", "Rückmeldeschlüssel", ReviewStatus.REJECTED, RelationScope.GLOBAL);
        FileTermRelationStore store = new FileTermRelationStore(directory);
        store.write(List.of(rejected), List.of(), List.of(
                new AuditEntry(rejected.id(), "reject", "USER:angelo", T0, ReviewStatus.REJECTED, 2)));
        Files.writeString(directory.resolve(FileTermRelationStore.AUDIT), "{\"relationId\":\"" + rejected.id(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertThat(store.history(rejected.id())).hasSize(1);
    }

    @Test
    void unreadableRelationsFileStopsInsteadOfDroppingData() throws Exception {
        Files.writeString(directory.resolve(FileTermRelationStore.RELATIONS), "{\"schema\":\"anders\"}");

        assertThatThrownBy(() -> new FileTermRelationStore(directory)).isInstanceOf(IllegalStateException.class);
    }
}
