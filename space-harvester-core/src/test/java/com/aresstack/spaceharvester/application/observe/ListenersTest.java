package com.aresstack.spaceharvester.application.observe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Empfänger eines Beobachters (ADR 0027): Filter, Abmelden, ein werfender Empfänger hält niemanden auf. */
class ListenersTest {

    @Test
    void aThrowingListenerStaysSubscribedAndTheOthersStillGetTheirMessage() {
        Listeners<Consumer<String>> listeners = new Listeners<>("Test");
        List<String> seen = new ArrayList<>();
        listeners.add(message -> {
            throw new IllegalStateException("kaputt");
        });
        listeners.add(message -> seen.add("b:" + message));

        listeners.deliver(null, listener -> listener.accept("eins"));
        listeners.deliver(null, listener -> listener.accept("zwei"));

        assertThat(seen).containsExactly("b:eins", "b:zwei");
    }

    @Test
    void theFilterSelectsTheRecipientsAndClosingTwiceIsHarmless() {
        Listeners<Consumer<String>> listeners = new Listeners<>("Test");
        List<String> seen = new ArrayList<>();
        Subscription a = listeners.add(message -> seen.add("a:" + message), "client-a"::equals);
        listeners.add(message -> seen.add("b:" + message), "client-b"::equals);

        listeners.deliver("client-a", listener -> listener.accept("eins"));
        a.close();
        a.close();
        listeners.deliver("client-a", listener -> listener.accept("zwei"));
        listeners.deliver("client-b", listener -> listener.accept("drei"));

        assertThat(seen).containsExactly("a:eins", "b:drei");
        assertThat(listeners.isEmpty()).isFalse();
    }
}
