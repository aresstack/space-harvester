package com.aresstack.spaceharvester.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRetryTest {

    @Test
    void transientFailuresAreRetriedAndEachReasonReported() {
        AtomicInteger calls = new AtomicInteger();
        List<String> reported = new ArrayList<>();
        String answer = ModelRetry.call(2, 0, (next, cause) -> reported.add(next + ":" + cause.reason()), () -> {
            if (calls.incrementAndGet() < 3) {
                throw new AiServiceException(calls.get() == 1 ? AiServiceException.Reason.UNREACHABLE
                        : AiServiceException.Reason.TIMEOUT, "weg");
            }
            return "ok";
        });
        assertThat(answer).isEqualTo("ok");
        assertThat(reported).containsExactly("2:UNREACHABLE", "3:TIMEOUT");
    }

    @Test
    void lastFailureKeepsItsReasonWhenAttemptsRunOut() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> ModelRetry.call(2, 0, (next, cause) -> { }, () -> {
            calls.incrementAndGet();
            throw new AiServiceException(AiServiceException.Reason.TIMEOUT, "zu langsam");
        })).isInstanceOfSatisfying(AiServiceException.class,
                e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.TIMEOUT));
        assertThat(calls).hasValue(3);
    }

    @Test
    void permanentFailuresAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> ModelRetry.call(5, 0, (next, cause) -> { }, () -> {
            calls.incrementAndGet();
            throw new AiServiceException(AiServiceException.Reason.UNAUTHORIZED, "Schlüssel");
        })).isInstanceOf(AiServiceException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void cancelledRunStopsBeforeTheNextAttempt() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> ModelRetry.call(5, 0, ModelRetry.logging(() -> true), () -> {
            calls.incrementAndGet();
            throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, "weg");
        })).isInstanceOf(CancellationException.class);
        assertThat(calls).hasValue(1);
    }
}
