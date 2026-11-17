package com.aresstack.spaceharvester.application.simplification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Vorstufe für eine Seite (ADR 0032, FL3), nur mit Schein-Modell. */
class SimplificationsTest {

    private static final PageKey PAGE = new PageKey("wiki", "1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"), ZoneOffset.UTC);

    private final InMemoryEnrichmentStore enrichments = new InMemoryEnrichmentStore();
    private final SimplificationStore store = new SimplificationStore(enrichments, CLOCK);

    private static SimplificationSection section(int heading, int... sentences) {
        List<SimplificationSection.Sentence> chosen = new ArrayList<>();
        for (int index : sentences) {
            chosen.add(new SimplificationSection.Sentence(index, "Der Techniker prüft die Pumpe " + index + "."));
        }
        return new SimplificationSection(List.of(new SimplificationSection.Sentence(heading, "Wartung " + heading)),
                chosen);
    }

    /** Antwortet je Eingabezeile mit einem T-Satz aus denselben Wörtern. */
    private static final Function<String, String> ECHO = input -> {
        StringBuilder out = new StringBuilder();
        for (String line : input.split("\n")) {
            String[] parts = line.split("\\|", 2);
            out.append(parts[0]).append("|T|").append(parts[1]).append('\n');
        }
        return out.toString();
    };

    private static final class FakeModel implements SimplificationModel {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> inputs = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String version = "fake:m1";
        volatile Function<String, String> answer = ECHO;

        @Override
        public String version() {
            return version;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String complete(String instructions, String input) {
            calls.incrementAndGet();
            inputs.add(input);
            return answer.apply(input);
        }
    }

    @Test
    void eachSectionIsOneModelCallAndTheResultIsKeptPerSentence() {
        FakeModel model = new FakeModel();
        Simplifications simplifications = new Simplifications(model, store, page -> true);
        Simplifications.Result result = simplifications.simplify(PAGE, "sha", List.of(section(0, 1, 2), section(5, 6)),
                2, () -> false);
        assertThat(model.calls).hasValue(2);
        assertThat(result.sentences()).containsOnlyKeys(1, 2, 6);
        assertThat(result.sentences().get(6)).extracting(SimplifiedSentence::text)
                .containsExactly("Der Techniker prüft die Pumpe 6.");
        assertThat(result.costs()).isEqualTo(new Simplifications.Costs(3, 2, 0, 0, 0, 0));
        assertThat(enrichments.find(PAGE)).extracting(e -> e.processorId())
                .containsExactly(Enrichments.SIMPLIFICATIONS);
    }

    @Test
    void aSecondRunCallsTheModelOnlyForChangedSentences() {
        FakeModel model = new FakeModel();
        Simplifications simplifications = new Simplifications(model, store, page -> true);
        simplifications.simplify(PAGE, "sha", List.of(section(0, 1, 2)), 1, () -> false);
        // Seitenfassung geändert: Satz 2 rückt auf 3 vor (gleicher Text bleibt gespeichert), neu ist Satz 4.
        List<SimplificationSection.Sentence> moved = List.of(
                new SimplificationSection.Sentence(1, "Der Techniker prüft die Pumpe 1."),
                new SimplificationSection.Sentence(3, "Der Techniker prüft die Pumpe 2."),
                new SimplificationSection.Sentence(4, "Der Techniker prüft die Pumpe 4."));
        SimplificationSection changed = new SimplificationSection(
                List.of(new SimplificationSection.Sentence(0, "Wartung 0")), moved);
        Simplifications.Result result = simplifications.simplify(PAGE, "sha2", List.of(changed), 1, () -> false);
        assertThat(model.calls).hasValue(2);
        assertThat(model.inputs.get(1)).isEqualTo("1|Wartung 0\n5|Der Techniker prüft die Pumpe 4.\n");
        assertThat(result.costs().fromStore()).isEqualTo(2);
        assertThat(result.sentences().get(3)).extracting(SimplifiedSentence::sentence).containsExactly(3);
    }

    @Test
    void anotherModelOrContextComputesAgain() {
        FakeModel model = new FakeModel();
        Simplifications simplifications = new Simplifications(model, store, page -> true);
        simplifications.simplify(PAGE, "sha", List.of(section(0, 1)), 1, () -> false);
        simplifications.simplify(PAGE, "sha", List.of(section(9, 1)), 1, () -> false);
        assertThat(model.calls).as("anderer Kontext").hasValue(2);
        model.version = "fake:m2";
        simplifications.simplify(PAGE, "sha", List.of(section(9, 1)), 1, () -> false);
        assertThat(model.calls).as("anderes Modell").hasValue(3);
    }

    @Test
    void withoutReleaseNothingGoesToTheModel() {
        FakeModel model = new FakeModel();
        Simplifications simplifications = new Simplifications(model, store, page -> false);
        assertThatThrownBy(() -> simplifications.simplify(PAGE, "sha", List.of(section(0, 1)), 1, () -> false))
                .isInstanceOfSatisfying(SimplificationUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(SimplificationUnavailableException.Reason.NOT_RELEASED));
        assertThat(model.calls).hasValue(0);
        assertThat(enrichments.find(PAGE)).isEmpty();
    }

    @Test
    void releaseWithdrawnDuringTheCallStoresNothing() {
        FakeModel model = new FakeModel();
        AtomicBoolean released = new AtomicBoolean(true);
        model.answer = input -> {
            released.set(false);
            return ECHO.apply(input);
        };
        Simplifications simplifications = new Simplifications(model, store, page -> released.get());
        assertThatThrownBy(() -> simplifications.simplify(PAGE, "sha", List.of(section(0, 1)), 1, () -> false))
                .isInstanceOf(SimplificationUnavailableException.class);
        assertThat(enrichments.find(PAGE)).isEmpty();
    }

    @Test
    void withdrawRemovesTheStoredSentencesOfPagesWithoutRelease() {
        FakeModel model = new FakeModel();
        Simplifications simplifications = new Simplifications(model, store, page -> true);
        simplifications.simplify(PAGE, "sha", List.of(section(0, 1)), 1, () -> false);
        PageKey other = new PageKey("wiki", "2");
        simplifications.simplify(other, "sha", List.of(section(0, 1)), 1, () -> false);
        assertThat(simplifications.withdraw(List.of(PAGE, other), page -> page.equals(other))).isEqualTo(1);
        assertThat(store.contains(PAGE)).isFalse();
        assertThat(store.contains(other)).isTrue();
    }

    @Test
    void aCancelledRunKeepsFinishedSectionsForTheNextRun() {
        FakeModel model = new FakeModel();
        AtomicBoolean cancel = new AtomicBoolean();
        model.answer = input -> {
            cancel.set(true);
            return ECHO.apply(input);
        };
        Simplifications simplifications = new Simplifications(model, store, page -> true);
        assertThatThrownBy(() -> simplifications.simplify(PAGE, "sha", List.of(section(0, 1), section(5, 6)), 1,
                cancel::get)).isInstanceOf(CancellationException.class);
        assertThat(model.calls).hasValue(1);
        model.answer = ECHO;
        Simplifications.Result result = simplifications.simplify(PAGE, "sha", List.of(section(0, 1), section(5, 6)),
                1, () -> false);
        assertThat(model.calls).as("nur der fehlende Abschnitt").hasValue(2);
        assertThat(result.costs().fromStore()).isEqualTo(1);
    }

    @Test
    void sectionsRunInParallelUpToTheLimit() {
        FakeModel model = new FakeModel();
        AtomicInteger running = new AtomicInteger();
        Set<Integer> peaks = ConcurrentHashMap.newKeySet();
        model.answer = input -> {
            peaks.add(running.incrementAndGet());
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return ECHO.apply(input);
        };
        Simplifications simplifications = new Simplifications(model, store, page -> true);
        simplifications.simplify(PAGE, "sha", List.of(section(0, 1), section(2, 3), section(4, 5), section(6, 7),
                section(8, 9)), 2, () -> false);
        assertThat(model.calls).hasValue(5);
        assertThat(peaks).allMatch(peak -> peak <= 2);
    }

    @Test
    void aFailingCallKeepsTheOtherSectionsAndReportsTheFailure() {
        FakeModel model = new FakeModel();
        model.answer = input -> {
            if (input.contains("Wartung 5")) {
                throw new IllegalStateException("Dienst antwortet nicht");
            }
            return ECHO.apply(input);
        };
        Simplifications simplifications = new Simplifications(model, store, page -> true);
        assertThatThrownBy(() -> simplifications.simplify(PAGE, "sha", List.of(section(0, 1), section(5, 6)), 1,
                () -> false)).isInstanceOf(IllegalStateException.class);
        assertThat(store.load(PAGE, simplifications.version())).hasSize(1);
    }
}
