package com.aresstack.writing4j.sourcerating;

/**
 * Where a host keeps source profiles between runs, so a run that was cancelled or interrupted does not describe the
 * same sources again. The library chooses the key (a fingerprint of the source's sample, the fence and the rating
 * settings) and the text (a profile in its own JSON form); the host only stores and returns the text, e.g. in a file
 * beside the basket. A profile is remembered as soon as it is made. Implementations are thread-safe.
 */
public interface ProfileMemory {

    /** Remembers nothing. */
    ProfileMemory NONE = new ProfileMemory() {
        @Override
        public String recall(String key) {
            return null;
        }

        @Override
        public void remember(String key, String profile) {
        }
    };

    /** The profile stored under {@code key}, or {@code null}. */
    String recall(String key);

    /** Stores {@code profile} under {@code key}; a failure to store must not end the run. */
    void remember(String key, String profile);
}
