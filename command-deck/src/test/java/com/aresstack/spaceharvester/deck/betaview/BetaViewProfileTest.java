package com.aresstack.spaceharvester.deck.betaview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class BetaViewProfileTest {

    @Test
    void normalisesAddressesLikeMainframeMate() {
        assertThat(BetaViewProfile.normalise("beta.example.org/betaview/login.action"))
                .isEqualTo(URI.create("https://beta.example.org/betaview/"));
        assertThat(BetaViewProfile.normalise("http://beta.example.org:8080/betaview"))
                .isEqualTo(URI.create("http://beta.example.org:8080/betaview/"));
        assertThat(BetaViewProfile.normalise("https://beta.example.org:443/"))
                .isEqualTo(URI.create("https://beta.example.org/"));
    }

    @Test
    void rejectsWhatIsNoAddress() {
        assertThatThrownBy(() -> BetaViewProfile.normalise(" ")).hasMessage("Adresse fehlt");
        assertThatThrownBy(() -> new BetaViewProfile(URI.create("ftp://host/"), "u"))
                .hasMessageContaining("http");
        assertThatThrownBy(() -> new BetaViewProfile(URI.create("https://host/"), " "))
                .hasMessage("Benutzer fehlt");
    }

    @Test
    void labelsWithUserAndHost() {
        assertThat(new BetaViewProfile(URI.create("https://beta.example.org/betaview/"), "pilot").label())
                .isEqualTo("pilot@beta.example.org");
    }

    @Test
    void theFilterFillsServerDefaults() {
        SearchFilter filter = new SearchFilter("F1", "de", null, null, " ", null, null, null, null, null, false,
                null, null);
        assertThat(filter.extension()).isEqualTo("*");
        assertThat(filter.needsSelgen()).isFalse();
        assertThat(filter.period().lastsel()).isEqualTo("last7days");
        SearchFilter titled = new SearchFilter("F1", "de", new SearchFilter.Period(
                SearchFilter.Period.Mode.DATE_RANGE, 0, null, "01.11.2026", "21.11.2026"), null, null, null, null,
                null, null, "Lauf", false, null, null);
        assertThat(titled.needsSelgen()).isTrue();
        assertThat(titled.period().lastsel()).isEqualTo("individual");
        assertThat(titled.period().lastdate()).isEqualTo("date");
        assertThatThrownBy(() -> new SearchFilter(" ", "de", null, null, null, null, null, null, null, null, false,
                null, null)).hasMessage("Favorit fehlt");
    }
}
