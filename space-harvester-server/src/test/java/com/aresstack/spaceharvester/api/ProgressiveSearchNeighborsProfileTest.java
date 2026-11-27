package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Suchmethode „Nachbarseiten“ (R2) am Dienst: nur auf Anfrage an, Ursache mit Ausgangsseite und Kante. */
class ProgressiveSearchNeighborsProfileTest {

    private static ProgressiveSearchController.ProgressiveRequest request(Boolean neighbors) {
        return new ProgressiveSearchController.ProgressiveRequest("Nachtlauf", null, null, null, null, null, null,
                null, null, null, null, null, null, null, neighbors, null, null, null);
    }

    @Test
    void theMethodIsOffUnlessRequested() {
        assertThat(ProgressiveSearchController.profile(request(null), SearchProfile.defaults()).neighbors())
                .isFalse();
        assertThat(ProgressiveSearchController.profile(request(false), SearchProfile.defaults()).neighbors())
                .isFalse();
        assertThat(ProgressiveSearchController.profile(request(true), SearchProfile.defaults()).neighbors())
                .isTrue();
    }

    @Test
    void aNeighborReasonNamesTheSeedAndTheEdge() {
        SearchController.HitReasonResponse response = SearchController.HitReasonResponse.of(HitReason.neighbor(
                "Nachtlauf", new HitReason.Neighbor("wiki", "1", "Betrieb", HitReason.Neighbor.Edge.LINKED_FROM)));

        assertThat(response.kind()).isEqualTo("neighbor");
        assertThat(List.of(response.viaSourceId(), response.viaPageId(), response.viaTitle(), response.viaEdge()))
                .containsExactly("wiki", "1", "Betrieb", "linked-from");
        assertThat(SearchController.HitReasonResponse.of(HitReason.semantic("Nachtlauf")).viaEdge()).isNull();
    }
}
