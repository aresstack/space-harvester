package com.aresstack.spaceharvester.deck.ndv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Bausteine des Natural Navigator aus MainframeMate: Namensfilter, Verlauf, Lesezeichen, Pfad und Artengruppen. */
class NdvNavigatorModelTest {

    @Test
    void theNameFilterWorksLikeRegexNameFilter() {
        NdvNameFilter filter = new NdvNameFilter();
        AtomicInteger changes = new AtomicInteger();
        filter.observe(changes::incrementAndGet);
        assertThat(filter.active()).isFalse();
        assertThat(filter.matches("LAGER001")).isTrue();

        filter.manual("^lag");
        assertThat(filter.matches("LAGER001")).as("ohne Rücksicht auf Groß- und Kleinschreibung").isTrue();
        assertThat(filter.matches("KASSE001")).isFalse();
        filter.toggle("kas", true);
        assertThat(filter.prefixes()).containsExactly("KAS");
        assertThat(filter.regex()).isEqualTo("^(\\QKAS\\E)");
        filter.toggle("LAG", true);
        assertThat(filter.matches("LAGER001")).isTrue();
        assertThat(filter.matches("KASSE001")).isTrue();
        assertThat(filter.matches("MENU")).isFalse();

        filter.manual("[");
        assertThat(filter.invalid()).isTrue();
        assertThat(filter.matches("MENU")).as("ungültiger Ausdruck filtert nichts").isTrue();
        filter.clear();
        assertThat(filter.active()).isFalse();
        assertThat(changes.get()).isEqualTo(5);

        filter.restore("", List.of("abc"));
        assertThat(filter.prefixes()).containsExactly("ABC");
        assertThat(changes.get()).as("Herstellen meldet nichts").isEqualTo(5);
        // Wie MainframeMate: kürzere Namen gehen ganz in die Auswahl.
        assertThat(NdvNameFilter.candidates(List.of("LAGER001", "lagern01", "KA", "KASSE"))).containsExactly("KA", "KAS",
                "LAG");
        assertThat(NdvNameFilter.split(NdvNameFilter.join(List.of("A", "B")))).containsExactly("A", "B");
    }

    @Test
    void theHistoryGoesBackAndForwardLikeABrowser() {
        NdvHistory history = new NdvHistory();
        assertThat(history.canBack()).isFalse();
        history.add("");
        history.add("LAGER");
        history.add("LAGER");
        history.add("KASSE");
        assertThat(history.back()).contains("LAGER");
        assertThat(history.back()).contains("");
        assertThat(history.back()).isEmpty();
        assertThat(history.forward()).contains("LAGER");
        history.add("SYSTEM");
        assertThat(history.canForward()).as("Neues Ziel verwirft den Vorwärts-Teil").isFalse();
        assertThat(history.back()).contains("LAGER");
    }

    @Test
    void bookmarksAndPathsReadLikeMainframeMate() {
        NdvBookmark object = NdvBookmark.parse("ndv://lager/LAGER001.NSP").orElseThrow();
        assertThat(object.library()).isEqualTo("LAGER");
        assertThat(object.objectName()).isEqualTo("LAGER001");
        assertThat(object.path()).isEqualTo("ndv://LAGER/LAGER001.NSP");
        assertThat(object.label()).isEqualTo("LAGER/LAGER001.NSP");
        assertThat(NdvBookmark.parse("ndv://KASSE").orElseThrow().label()).isEqualTo("KASSE");
        assertThat(NdvBookmark.parse(" ")).isEmpty();
        assertThatThrownBy(() -> new NdvBookmark(" ", "X")).hasMessageContaining("Bibliothek");

        NdvProfile profile = new NdvProfile("10.0.0.7", NdvProfile.DEFAULT_PORT, " lager ");
        assertThat(profile.port()).isEqualTo(8011);
        assertThat(profile.defaultLibrary()).isEqualTo("LAGER");
        assertThat(profile.libraryFromPath("10.0.0.7:8011")).isEmpty();
        assertThat(profile.libraryFromPath("")).isEmpty();
        assertThat(profile.libraryFromPath("10.0.0.7:8011/kasse")).isEqualTo("KASSE");
        assertThat(profile.libraryFromPath("irgendwo/SYSTEM")).isEqualTo("SYSTEM");
        assertThat(profile.libraryFromPath("lager")).isEqualTo("LAGER");
    }

    @Test
    void typeGroupsFollowTheNavigatorOrder() {
        assertThat(NdvTypeGroups.ORDER.getFirst()).isEqualTo(NdvTypeGroups.PROGRAM);
        assertThat(NdvTypeGroups.groupName(NdvTypeGroups.PROGRAM)).isEqualTo("Programs");
        assertThat(NdvTypeGroups.groupName(NdvTypeGroups.DDM)).isEqualTo("DDMs");
        assertThat(NdvTypeGroups.groupName(4711)).isEqualTo("Sonstige");
        assertThat(NdvTypeGroups.rank(NdvTypeGroups.PROGRAM)).isLessThan(NdvTypeGroups.rank(NdvTypeGroups.DDM));
        assertThat(NdvTypeGroups.rank(4711)).isGreaterThan(NdvTypeGroups.rank(NdvTypeGroups.RESOURCE));
    }
}
