package com.aresstack.spaceharvester.application.observe;

/**
 * Eine Anmeldung bei einem Beobachter des Core (ADR 0027). {@link #close()} meldet ab und darf mehrfach gerufen werden;
 * danach kommt keine neue Zustellung mehr, eine gerade laufende darf noch enden.
 */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

    /** Meldet ab; ohne Wirkung, wenn schon abgemeldet. */
    @Override
    void close();

    /** Eine Anmeldung, die nichts abmeldet, etwa ohne Beobachter. */
    Subscription NONE = () -> { };
}
