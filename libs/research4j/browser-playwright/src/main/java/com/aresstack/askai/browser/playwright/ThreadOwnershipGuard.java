package com.aresstack.askai.browser.playwright;

/**
 * Captures the thread it was created on and rejects use from any other thread. Playwright Java is not
 * thread-safe AND only dispatches its events (route interception, exposeBinding, popups) while the owning
 * thread runs a Playwright call — so every Playwright object in this browser runtime is created and used on exactly
 * one owner thread (the {@link BrowserSessionActor} loop). This guard turns an accidental direct call from an
 * MCP HTTP worker into an immediate, readable failure instead of a latent race or a frozen browser.
 */
final class ThreadOwnershipGuard {

    private final Thread owner = Thread.currentThread();

    /** @throws IllegalStateException when called from any thread other than the creating one. */
    void check() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Playwright touched from thread '"
                    + Thread.currentThread().getName() + "' — all Playwright access must run on the "
                    + "owner thread '" + owner.getName() + "' (route calls through BrowserSessionActor).");
        }
    }

    Thread owner() {
        return owner;
    }
}
