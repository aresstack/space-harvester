package com.aresstack.knowledge4j.basket;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceLocation;
import com.aresstack.knowledge4j.source.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceBasketTest {

    private static final SourceRef A = new SourceRef(SourceKind.INTERNAL_DOCUMENT, "doc-1", "3",
            SourceLocation.WHOLE, "Anleitung");
    private static final SourceRef B = SourceRef.of(SourceKind.WEB_PAGE, "https://example.org/b");

    @Test
    void aSourceIsInTheBasketOnceAndTheFirstReferenceStays() {
        SourceBasket basket = new SourceBasket(List.of(A, B, A.withRevision("4"), A.withTitle("anders")));
        assertEquals(List.of(A, B), basket.sources());
    }

    @Test
    void aFurtherSearchAddsSourcesAndKeepsTheOldOnes() {
        SourceBasket basket = new SourceBasket(List.of(A)).with(List.of(B, A.withRevision("5")));
        assertEquals(List.of(A, B), basket.sources());
        assertTrue(SourceBasket.empty().isEmpty());
        assertEquals("SourceBasket[2 sources, fence " + Fence.of("").basis() + ", 0 hints, 0 questions]", basket.toString());
    }

    @Test
    void theBasketCarriesItsFenceThroughFurtherSearchesAndTheReaderCanWidenIt() {
        Fence fence = new Fence("Regenwasser im Kleingarten", "", List.of("Zisternen"), List.of("Hausdach"), "", "");
        SourceBasket basket = new SourceBasket(fence, List.of(A)).with(List.of(B));
        assertEquals(fence.basis(), basket.fence().basis());
        assertEquals(List.of(A, B), basket.sources());

        SourceBasket answered = basket.withFence(basket.fence().widened(List.of("Bewässerung", "Zisternen"),
                List.of("Gründach")));
        assertEquals(List.of("Zisternen", "Bewässerung"), answered.fence().getIncluded());
        assertEquals(List.of("Hausdach", "Gründach"), answered.fence().getExcluded());
        assertEquals(List.of(A, B), answered.sources());
        assertEquals("", new SourceBasket(List.of(A)).fence().getMission());
    }

    @Test
    void theReadersOrderingHintsTravelInTheBasketAsTheyAreGiven() {
        OrderingHint tank = new OrderingHint("Zisterne", "Regenwasser speichern");
        OrderingHint barrel = new OrderingHint(" Regentonne ", "Regenwasser speichern");
        SourceBasket basket = new SourceBasket(List.of(A)).withHints(List.of(tank, barrel, tank));
        assertEquals(List.of(tank, barrel), basket.hints());
        assertEquals("Regentonne", basket.hints().get(1).term());
        assertEquals(basket.hints(), basket.with(List.of(B)).withFence(Fence.of("Regenwasser")).hints());
        assertTrue(new SourceBasket(List.of(A)).hints().isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new OrderingHint("Zisterne", "zisterne"));
    }

    @Test
    void theReadersQuestionsTravelBesideTheFenceOnceEachAndNeverInTheText() {
        SourceBasket basket = new SourceBasket(Fence.of("Regenwasser"), List.of(A), null,
                java.util.Arrays.asList(" Wie groß muss eine Zisterne sein? ", "", null, "Wie groß muss eine Zisterne sein?",
                        "Was kostet eine Regentonne?"));
        assertEquals(List.of("Wie groß muss eine Zisterne sein?", "Was kostet eine Regentonne?"), basket.questions());
        assertEquals(basket.questions(), basket.with(List.of(B)).withFence(Fence.of("Regen"))
                .withHints(List.of(new OrderingHint("Zisterne", "Regenwasser"))).questions());
        assertEquals(List.of("Neu?"), basket.withQuestions(List.of("Neu?")).questions());
        assertTrue(new SourceBasket(List.of(A)).questions().isEmpty());
        assertTrue(basket.toString().endsWith("2 questions]"));
        assertTrue(!basket.toString().contains("Zisterne"));
    }
}
