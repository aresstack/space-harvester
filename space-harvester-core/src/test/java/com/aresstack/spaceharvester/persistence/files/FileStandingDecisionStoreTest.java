package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.standing.Standing;
import com.aresstack.spaceharvester.application.standing.StandingDecision;
import com.aresstack.spaceharvester.application.standing.StandingDecisionStore.AuditEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ablage der Entscheidungen zum Stand als Dateien (ADR 0032, FL6). */
class FileStandingDecisionStoreTest {

    private static final Instant AT = Instant.parse("2026-11-17T08:00:00Z");
    private static final PageKey PAGE = new PageKey("wiki", "1");
    private static final String STATEMENT = "wiki/1@aaaaaaaaaaaa#s0.v2";

    @TempDir
    Path directory;

    @Test
    void decisionsAndTheirHistorySurviveARestart() throws Exception {
        FileStandingDecisionStore store = new FileStandingDecisionStore(directory);
        StandingDecision page = decision(null, Standing.UEBERHOLT, 1);
        StandingDecision first = decision(STATEMENT, Standing.GEPLANT, 1);
        StandingDecision second = decision(STATEMENT, Standing.GILT, 2);
        store.write(List.of(page, first), List.of(), List.of(audit(page, "set"), audit(first, "set")));
        store.write(List.of(second), List.of(), List.of(audit(second, "set")));
        store.write(List.of(), List.of(page.target()), List.of(audit(page, "remove")));
        Files.writeString(directory.resolve(FileStandingDecisionStore.AUDIT), "{abgebrochen\n",
                StandardOpenOption.APPEND);

        FileStandingDecisionStore reopened = new FileStandingDecisionStore(directory);

        assertThat(reopened.all()).containsExactly(second);
        assertThat(reopened.find(second.target())).contains(second);
        assertThat(reopened.history(second.target())).extracting(AuditEntry::standing, AuditEntry::revision)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Standing.GEPLANT, 1L),
                        org.assertj.core.groups.Tuple.tuple(Standing.GILT, 2L));
        assertThat(reopened.history(page.target())).extracting(AuditEntry::action).containsExactly("set", "remove");
    }

    @Test
    void anUnreadableFileStopsTheStartInsteadOfDroppingDecisions() throws Exception {
        Files.writeString(directory.resolve(FileStandingDecisionStore.DECISIONS), "{\"schema\":\"anders/v9\"}");

        assertThatThrownBy(() -> new FileStandingDecisionStore(directory)).isInstanceOf(IllegalStateException.class);
    }

    private static StandingDecision decision(String statementId, Standing standing, long revision) {
        return new StandingDecision(PAGE, statementId, standing, revision, "angelo", AT, "angelo", AT);
    }

    private static AuditEntry audit(StandingDecision decision, String action) {
        return new AuditEntry(decision.target(), action, "angelo", AT, decision.standing(), decision.revision());
    }
}
