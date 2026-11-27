package com.aresstack.knowledge4j.run;

/** Receives progress of long work. {@code total} is 0 while unknown. Never receives content, only counts. */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = (stage, done, total) -> {
    };

    void onProgress(String stage, long done, long total);
}
