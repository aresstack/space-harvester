package com.aresstack.spaceharvester.deck.betaview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.Extension;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.ExtensionMode;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.Report;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.ReportMode;
import org.junit.jupiter.api.Test;

/** Die Auswahllisten liefern dieselben Werte wie die Filterfelder in MainframeMate. */
class FilterPatternsTest {

    @Test
    void choiceListsWithFreeValue() {
        assertThat(FilterPatterns.choice("APZF", "", true)).isEqualTo("APZF");
        assertThat(FilterPatterns.choice(FilterPatterns.CUSTOM, " XY12 ", true)).isEqualTo("XY12");
        assertThat(FilterPatterns.choice(FilterPatterns.CUSTOM, "", true)).isEqualTo("*");
        // Jobname: leerer freier Wert bleibt leer (JobNameFilterPanel).
        assertThat(FilterPatterns.choice(FilterPatterns.CUSTOM, "", false)).isEmpty();

        assertThat(FilterPatterns.parseChoice(FilterPatterns.FORMS, "ATAB")).containsExactly("ATAB", "");
        assertThat(FilterPatterns.parseChoice(FilterPatterns.FORMS, "QQQQ"))
                .containsExactly(FilterPatterns.CUSTOM, "QQQQ");
        assertThat(FilterPatterns.parseChoice(FilterPatterns.WILDCARD, "")).containsExactly("*", "");
        assertThat(FilterPatterns.FORMS).extracting(FilterPatterns.Option::value)
                .containsExactly("*", "APZC", "APZF", "ATZC", "ATAB", "APZD", "ATZD", "APAB", FilterPatterns.CUSTOM);
        assertThat(FilterPatterns.JOB_NAMES).extracting(FilterPatterns.Option::value)
                .containsExactly("*", "APABBBNK", "APABHOLE", "APABDAUB", FilterPatterns.CUSTOM);
    }

    @Test
    void extensionsAreBuiltAndParsedLikeMainframeMate() {
        assertThat(new Extension(ExtensionMode.BK_FREE, "1x8", "F05*").value()).isEqualTo("BK18F05*");
        assertThat(new Extension(ExtensionMode.BK_BANK, "18", "*").value()).isEqualTo("BK18BANK*");
        assertThat(new Extension(ExtensionMode.CONSTANT, "WV00BANK", "").value()).isEqualTo("WV00BANK");
        assertThat(new Extension(ExtensionMode.BEW_FB, "1234", "").value()).isEqualTo("B1234F13FB");
        assertThat(new Extension(ExtensionMode.BEW_LB, "1234", "").value()).isEqualTo("B1234F13LB");
        assertThat(new Extension(ExtensionMode.CUSTOM, "FREI", "").value()).isEqualTo("FREI");

        for (String value : new String[] {"BK18F05*", "BK18BANK*", "WV00BANK", "*", "B1234F13FB", "B1234F13LB",
                "FREI"}) {
            assertThat(Extension.parse(value).value()).as(value).isEqualTo(value);
        }
        // Platzhalter in der Nummer bleiben als freier Wert erhalten.
        for (String value : new String[] {"BK*BANK", "B*F13FB", "B12*F13LB", "BK1*F05"}) {
            assertThat(Extension.parse(value)).as(value).isEqualTo(new Extension(ExtensionMode.CUSTOM, value, ""));
        }
        assertThat(Extension.parse("").mode()).isEqualTo(ExtensionMode.CONSTANT);
        assertThat(Extension.parse("BK18BANK*")).isEqualTo(new Extension(ExtensionMode.BK_BANK, "18", "*"));
    }

    @Test
    void reportsAreBuiltAndParsedLikeMainframeMate() {
        assertThat(new Report(ReportMode.ANY, "", "").value()).isEqualTo("*");
        assertThat(new Report(ReportMode.PREFIX, "*E", "").value()).isEqualTo("*E");
        assertThat(new Report(ReportMode.BA, "BA002", "V").value()).isEqualTo("BA002V");
        assertThat(new Report(ReportMode.K9999, "101", "A").value()).isEqualTo("K9999BA101A");
        assertThat(new Report(ReportMode.BEWIRT, "4711", "").value()).isEqualTo("%4711*");
        assertThat(new Report(ReportMode.BEWIRT, "", "").value()).isEqualTo("*");

        for (String value : new String[] {"*", "*A", "BA001L", "K9999BA200E", "%4711*", "FREI"}) {
            assertThat(Report.parse(value).value()).as(value).isEqualTo(value);
        }
        for (String value : new String[] {"BA001*", "BA999A", "K9999BA1*1A", "K9999BA101X", "*4711*"}) {
            assertThat(Report.parse(value)).as(value).isEqualTo(new Report(ReportMode.CUSTOM, value, ""));
        }
        assertThat(FilterPatterns.baTypes("BA002")).extracting(FilterPatterns.Option::value)
                .containsExactly("B", "F", "V");
    }
}
