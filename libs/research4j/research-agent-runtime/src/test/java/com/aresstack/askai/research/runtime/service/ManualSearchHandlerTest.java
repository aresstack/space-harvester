package com.aresstack.askai.research.runtime.service;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The runtime manual-search handler runs the SAME productive {@link SearchStrategy} the loop uses and emits
 * typed {@code manual_search_*} events. It is phase-independent by construction (no host state input at all),
 * and every failure mode is an HONEST failure event — never a silent no-op.
 */
public class ManualSearchHandlerTest {

    private static final BooleanSupplier NOT_CANCELLED = new BooleanSupplier() {
        public boolean getAsBoolean() {
            return false;
        }
    };

    private final List<String> lines = new ArrayList<String>();
    private final ManualSearchHandler.Emitter emitter = new ManualSearchHandler.Emitter() {
        public void emit(String line) {
            lines.add(line);
        }
    };

    @Test
    public void runsTheStrategyAndEmitsStartedThenCompleted() {
        final String[] seenQuery = new String[1];
        final String[] seenLanguage = new String[1];
        final String[] seenCountry = {"unset"};
        SearchStrategy strategy = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                seenQuery[0] = r.getQuery();
                seenLanguage[0] = r.getLanguage();
                seenCountry[0] = r.getCountry();
                return InitialSearchResult.empty(Collections.<String>emptyList());
            }
        };
        new ManualSearchHandler(strategy, NOT_CANCELLED).handle("R1", "wearables audio", "de", emitter);

        assertEquals("the productive strategy ran with exactly the query", "wearables audio", seenQuery[0]);
        assertEquals("the language snapshot overrides the provider default", "de", seenLanguage[0]);
        assertEquals("the country is never derived from the language", null, seenCountry[0]);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("manual_search_started"));
        assertTrue(lines.get(0).contains("request_id=R1"));
        assertTrue(lines.get(1).contains("manual_search_completed"));
        assertTrue(lines.get(1).contains("request_id=R1"));
    }

    @Test
    public void aMissingLanguageKeepsTheProviderDefault() {
        final String[] seenLanguage = {"unset"};
        SearchStrategy strategy = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                seenLanguage[0] = r.getLanguage();
                return InitialSearchResult.empty(Collections.<String>emptyList());
            }
        };
        new ManualSearchHandler(strategy, NOT_CANCELLED).handle("R1", "q", null, emitter);
        assertEquals("a legacy request without a language keeps the provider default",
                null, seenLanguage[0]);
    }

    @Test
    public void aMissingStrategyIsAnHonestUnavailableFailureNotANoOp() {
        new ManualSearchHandler(null, NOT_CANCELLED).handle("R1", "q", null, emitter);
        assertTrue(lines.get(0).contains("manual_search_started"));
        assertTrue(lines.get(1).contains("manual_search_failed"));
        assertTrue(lines.get(1).contains("reason=SEARCH_UNAVAILABLE"));
    }

    @Test
    public void aStrategyFailureIsAFailedEvent() {
        SearchStrategy strategy = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                throw new RuntimeException("provider down");
            }
        };
        new ManualSearchHandler(strategy, NOT_CANCELLED).handle("R1", "q", null, emitter);
        assertTrue(lines.get(1).contains("manual_search_failed"));
        assertTrue(lines.get(1).contains("reason=SEARCH_FAILED"));
    }

    @Test
    public void cancellationSurfacesAsCancelledFailure() {
        SearchStrategy strategy = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                return InitialSearchResult.empty(Collections.<String>emptyList());
            }
        };
        BooleanSupplier cancelled = new BooleanSupplier() {
            public boolean getAsBoolean() {
                return true;
            }
        };
        new ManualSearchHandler(strategy, cancelled).handle("R1", "q", null, emitter);
        assertTrue(lines.get(1).contains("manual_search_failed"));
        assertTrue(lines.get(1).contains("reason=CANCELLED"));
    }

    @Test
    public void anEmptyQueryIsRejectedBeforeTouchingTheStrategy() {
        new ManualSearchHandler(null, NOT_CANCELLED).handle("R1", "   ", null, emitter);
        assertTrue(lines.get(0).contains("manual_search_started"));
        assertTrue(lines.get(1).contains("reason=EMPTY_QUERY"));
    }
}
