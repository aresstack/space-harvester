package com.aresstack.spaceharvester.application.stepmodel;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.ModelRetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Jeder Analyseschritt wiederholt vorübergehende Fehler mindestens zweimal (ADR 0035, P1b). */
class StepModelRetryTest {

    private long backoff;

    @BeforeEach
    void fastBackoff() {
        backoff = ModelRetry.defaultBackoffMillis;
        ModelRetry.defaultBackoffMillis = 0;
    }

    @AfterEach
    void restoreBackoff() {
        ModelRetry.defaultBackoffMillis = backoff;
    }

    @Test
    void aFailedStepIsRetriedAtLeastTwiceEvenIfTheModelSaysLess() {
        List<AiServiceException.Reason> reasons = new ArrayList<>();
        Flaky model = new Flaky(2, 0);
        assertThat(model.completeRetrying("Anweisung", "Eingabe", (next, cause) -> reasons.add(cause.reason())))
                .isEqualTo("Antwort");
        assertThat(model.calls).isEqualTo(3);
        assertThat(reasons).containsExactly(AiServiceException.Reason.UNREACHABLE,
                AiServiceException.Reason.UNREACHABLE);
    }

    private static final class Flaky implements StepModel {

        private final int failures;
        private final int retries;
        int calls;

        Flaky(int failures, int retries) {
            this.failures = failures;
            this.retries = retries;
        }

        @Override
        public String version() {
            return "flaky";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int retries() {
            return retries;
        }

        @Override
        public String complete(String instructions, String input) {
            if (++calls <= failures) {
                throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, "Dienst nicht erreichbar");
            }
            return "Antwort";
        }
    }
}
