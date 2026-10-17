package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import org.junit.jupiter.api.Test;

/** Die Meldungen nennen den gescheiterten Schritt; ein Seitenabruf verschweigt die Ursache des Core nicht. */
class ProblemsTest {

    private static final SourceAccessException UNAVAILABLE = new SourceAccessException("wiki",
            SourceAccessException.Reason.UNAVAILABLE, "Seite 42: Confluence nicht erreichbar (Lese-Timeout)", null);

    @Test
    void eachStepHasItsOwnHeading() {
        assertThat(Problems.describe(UNAVAILABLE, Problems.Step.SPACES).title())
                .isEqualTo("Spaces nicht abrufbar: Quelle nicht erreichbar");
        assertThat(Problems.describe(UNAVAILABLE, Problems.Step.PAGE_LIST).title())
                .isEqualTo("Seitenliste nicht abrufbar: Quelle nicht erreichbar");
        assertThat(Problems.describe(UNAVAILABLE, Problems.Step.PAGE).title())
                .isEqualTo("Seite nicht geladen: Quelle nicht erreichbar");
        assertThat(Problems.describe(new CacheMissException("wiki", "42"), Problems.Step.STOCK).title())
                .startsWith("Lokaler Bestand nicht lesbar");
    }

    @Test
    void aFailedPageNamesTheCauseAndThatTheConnectionCheckAsksSomethingElse() {
        Problems.Problem problem = Problems.describe(UNAVAILABLE, Problems.Step.PAGE);

        assertThat(problem.message()).contains("Lese-Timeout");
        assertThat(problem.hint()).contains("Verbindungsprüfung fragt nur die Spaces ab");
    }
}
