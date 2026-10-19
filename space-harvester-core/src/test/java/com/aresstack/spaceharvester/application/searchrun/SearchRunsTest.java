package com.aresstack.spaceharvester.application.searchrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.UserCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ein Lauf enthält den Suchtext: Lesen dürfen ihn nur sein Aufrufer und Verwalter, nie ein fremder Endnutzer. */
class SearchRunsTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final AccessContext OTHER = AccessContext.technicalClient("other");
    private static final AccessContext ADMIN = AccessContext.technicalClient("admin");
    private static final AccessContext USER = AccessContext.endUser("u", "app", new UserCredential("wiki", "t"));
    private static final AccessContext SAME_USER_OTHER_CLIENT = AccessContext.endUser("u", "admin",
            new UserCredential("wiki", "t"));

    private final InMemorySearchRunLog log = new InMemorySearchRunLog();
    private final SearchRuns runs = new SearchRuns(log, caller -> "admin".equals(caller.principal()));

    private void record(String id, String partOf, AccessContext owner, int second) {
        log.record(new SearchRun(new SearchRunId(id), partOf == null ? null : new SearchRunId(partOf),
                SearchRun.Kind.KEYWORD, owner.jobOwner(), "geheime Frage", Instant.parse("2026-10-18T08:00:00Z")
                .plusSeconds(second), Duration.ZERO, List.of(), List.of(), true, null));
    }

    @Test
    void onlyTheCallerAndAdministratorsReadARun() {
        record("r1", null, APP, 0);
        record("k1", "r1", APP, 1);

        assertThat(runs.find(APP, "r1")).hasValueSatisfying(view -> {
            assertThat(view.run().id().value()).isEqualTo("r1");
            assertThat(view.parts()).extracting(part -> part.id().value()).containsExactly("k1");
        });
        assertThat(runs.find(OTHER, "r1")).isEmpty();
        assertThat(runs.find(USER, "r1")).isEmpty();
        assertThat(runs.find(ADMIN, "r1")).isPresent();
        assertThat(runs.find(APP, "kein gültiger/Name")).isEmpty();
        assertThat(runs.find(null, "r1")).isEmpty();
    }

    @Test
    void anEndUserSeesOnlyRunsThroughTheSameClientAndNeverInheritsAdministration() {
        record("u1", null, USER, 0);
        record("a1", null, APP, 1);

        assertThat(runs.recent(USER, 10)).extracting(run -> run.id().value()).containsExactly("u1");
        // Derselbe Benutzer über den Verwalter-Client ist ein anderer Eigentümer und kein Verwalter.
        assertThat(runs.recent(SAME_USER_OTHER_CLIENT, 10)).isEmpty();
        assertThat(runs.find(SAME_USER_OTHER_CLIENT, "u1")).isEmpty();
        assertThat(runs.recent(ADMIN, 10)).extracting(run -> run.id().value()).containsExactly("a1", "u1");
        assertThat(runs.recent(OTHER, 10)).isEmpty();
    }

    @Test
    void withoutLogNothingIsEnabled() {
        assertThat(new SearchRuns(SearchRunLog.NONE, caller -> true).enabled()).isFalse();
        assertThat(runs.enabled()).isTrue();
    }

    @Test
    void toStringNamesNeitherQueryNorOwner() {
        record("r1", null, APP, 0);
        assertThat(log.find(new SearchRunId("r1")).orElseThrow().toString()).doesNotContain("geheime")
                .doesNotContain("app");
    }
}
