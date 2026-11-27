package com.aresstack.research4j.basket;

import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.access.SourceHit;
import com.aresstack.knowledge4j.access.SourceSearchPort;
import com.aresstack.knowledge4j.basket.SourceBasket;
import com.aresstack.knowledge4j.policy.RunGuard;
import com.aresstack.knowledge4j.policy.TransferPolicy;
import com.aresstack.knowledge4j.policy.TransferTarget;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.research4j.InventedStock;
import com.aresstack.research4j.ScriptedModel;
import com.aresstack.research4j.acquire.FetchChannel;
import com.aresstack.research4j.acquire.SearchChannel;
import com.aresstack.research4j.concept.SearchConcept;
import com.aresstack.research4j.judge.FenceFit;
import com.aresstack.research4j.judge.SourceProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance of the research core: a whole research against an invented internal knowledge base, with research-core
 * and ports in memory only, no browser library on the class path.
 */
class BasketResearchTest {

    private static final Fence FENCE = new Fence("Regenwasser im Kleingarten nutzen",
            "Nur Sammeln und Nutzen von Regenwasser", List.of("Regentonne", "Zisterne"),
            List.of("Vereinsleben"), "", "");

    private static final SearchConcept CONCEPT = new SearchConcept(List.of(
            SearchConcept.Node.root("root", "Regenwasser im Kleingarten"),
            SearchConcept.Node.child("tonne", "Regentonne", "root"),
            SearchConcept.Node.child("zisterne", "Zisterne", "root")));

    private final InventedStock stock = new InventedStock();
    private final ScriptedModel model = new ScriptedModel();
    private final Map<String, FetchedContent> hostStock = new ConcurrentHashMap<>();

    private BasketResearch research(BasketResearch.Settings settings, List<SearchChannel> extra) {
        List<SearchChannel> searches = new ArrayList<>();
        searches.add(new SearchChannel(TransferTarget.internal("index"), stock, 10));
        searches.addAll(extra);
        SourceKeeper keeper = content -> {
            hostStock.put(content.ref().id(), content);
            return content.ref();
        };
        RunGuard guard = new RunGuard(TransferPolicy.internalStaysInternal(),
                Provenance.classified(Classification.INTERNAL));
        return new BasketResearch(model, searches, List.of(new FetchChannel(TransferTarget.internal("docs"), stock)),
                keeper, guard, settings);
    }

    private static BasketResearch.Settings settings(int capacity, int access) {
        return BasketResearch.Settings.defaults("de", capacity, access);
    }

    private static List<String> ids(SourceBasket basket) {
        return basket.sources().stream().map(SourceRef::id).toList();
    }

    @Test
    void everySourceButAClearlyOutsideOneGoesIntoTheBasket() {
        BasketResearch.Result result = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, null, null, null);

        List<String> basket = ids(result.basket());
        assertTrue(basket.containsAll(List.of("regentonne", "zisterne", "wasser", "notiz")), basket.toString());
        assertEquals(FENCE.basis(), result.basket().fence().basis(), "the basket carries its fence into Phase 4");
        assertFalse(basket.contains("grillfest"), "outside the fence");
        assertTrue(result.profiles().stream().anyMatch(p -> p.source().id().equals("grillfest")
                && p.fit() == FenceFit.AUSSEN));
        assertFalse(basket.contains("leer"), "no readable text");
        assertTrue(result.skipped().stream().anyMatch(s -> s.what().equals("leer")));
    }

    @Test
    void theBasketHoldsReferencesAndTheHostKeepsTheText() {
        BasketResearch.Result result = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, null, null, null);

        for (SourceRef ref : result.basket().sources()) {
            assertTrue(hostStock.containsKey(ref.id()), ref.id());
        }
        assertEquals(stock.doc("zisterne").text(), hostStock.get("zisterne").text());
    }

    @Test
    void childPagesOfAnAcceptedSourceAreFollowedAsDeepAsSet() {
        BasketResearch.Result followed = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, null, null, null);
        assertTrue(ids(followed.basket()).contains("ueberlauf"), "child page reached over the link");

        BasketResearch.Settings flat = new BasketResearch.Settings("de", 1, 1, 3, 2, 2, 0, 1, null, null);
        BasketResearch.Result notFollowed = research(flat, List.of()).run(FENCE, CONCEPT, null, null, null);
        assertFalse(ids(notFollowed.basket()).contains("ueberlauf"));
    }

    @Test
    void oneGraphicsCardAndACloudServiceFillTheSameBasket() {
        BasketResearch.Result one = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, null, null, null);
        BasketResearch.Result many = new BasketResearchTest().research(settings(6, 4), List.of())
                .run(FENCE, CONCEPT, null, null, null);

        assertEquals(ids(one.basket()), ids(many.basket()));
        assertEquals(one.searches().stream().map(BasketResearch.SearchRecord::query).toList(),
                many.searches().stream().map(BasketResearch.SearchRecord::query).toList());
        assertEquals(one.rounds(), many.rounds());
        assertEquals(one.stop(), many.stop());
    }

    @Test
    void theResearchEndsAtAStandstillWithoutAFixedNumberOfRounds() {
        BasketResearch.Result result = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, null, null, null);

        assertEquals(BasketResearch.Stop.STILLSTAND, result.stop());
        assertTrue(result.rounds() > 2, "nodes not covered are planned again: " + result.rounds());
        assertTrue(result.searches().stream().anyMatch(s -> s.query().contains("Runde 2")),
                "a later cycle changes the strategy");
        BasketResearch.NodeCoverage zisterne = result.coverage().stream()
                .filter(c -> c.nodeId().equals("zisterne")).findFirst().orElseThrow();
        assertFalse(zisterne.covered(), "one document on cisterns is no coverage");
    }

    @Test
    void anInternalTaskNeverSendsAQueryToAnExternalSearch() {
        AtomicInteger asked = new AtomicInteger();
        SourceSearchPort web = (query, cancel) -> {
            asked.incrementAndGet();
            return List.<SourceHit>of();
        };
        BasketResearch.Result result = research(settings(1, 1),
                List.of(new SearchChannel(TransferTarget.external("websuche"), web, 10)))
                .run(FENCE, CONCEPT, null, null, null);

        assertEquals(0, asked.get(), "the policy stops every query before it leaves");
        assertTrue(result.skipped().stream().anyMatch(s -> s.what().equals("websuche")
                && s.reason() == SourceAccessException.Reason.DENIED_BY_POLICY));
        assertTrue(ids(result.basket()).contains("zisterne"), "the internal search still works");
    }

    @Test
    void aFailedJudgementIsTriedOnceMoreInTheNextRound() {
        model.failOnce.add("zisterne");
        BasketResearch.Result result = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, null, null, null);

        assertTrue(ids(result.basket()).contains("zisterne"));
        assertTrue(result.modelFailures() >= 1);
    }

    @Test
    void aSourceAlreadyInTheBasketIsNotReadAgain() {
        SourceBasket start = new SourceBasket(List.of(InventedStock.ref("zisterne")));
        BasketResearch.Result result = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, start, null, null);

        assertEquals("zisterne", ids(result.basket()).get(0));
        assertFalse(hostStock.containsKey("zisterne"), "not read and kept a second time");
    }

    @Test
    void aCancelledResearchKeepsWhatItFound() {
        AtomicBoolean cancel = new AtomicBoolean();
        BasketResearch.Result result = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, null, cancel::get,
                (step, done, total) -> {
                    if (step.equals(BasketResearch.STEP_JUDGE) && done == total && total > 0) {
                        cancel.set(true);
                    }
                });

        assertEquals(BasketResearch.Stop.ABGEBROCHEN, result.stop());
        assertEquals(1, result.rounds());
        assertFalse(result.basket().isEmpty());
    }

    @Test
    void withoutAMindMapTheMissionSteersTheSearch() {
        BasketResearch.Result result = research(settings(1, 1), List.of()).run(FENCE, null, null, null, null);

        assertEquals("Regenwasser im Kleingarten nutzen", result.searches().get(0).query());
        assertTrue(ids(result.basket()).contains("regentonne"));
        assertEquals(1, result.coverage().size());
    }

    @Test
    void theProfilesNameTheSourceAndTheReason() {
        BasketResearch.Result result = research(settings(1, 1), List.of()).run(FENCE, CONCEPT, null, null, null);

        for (SourceProfile profile : result.profiles()) {
            assertTrue(profile.judged());
            assertFalse(profile.reason().isEmpty());
        }
    }
}
