package com.aresstack.writing4j.cluster;

/**
 * PR 20: progress and cancel for a clustering run. {@link #progress} reports work units done out of a total
 * (the similarity matrix rows first, then the merges); {@link #isCancelled} is polled between units, and a
 * cancelled run ends with {@link ClusteringCancelledException} without a partial result.
 */
public interface ClusteringMonitor {

    ClusteringMonitor NONE = new ClusteringMonitor() {
        public void progress(String stage, long done, long total) {
        }

        public boolean isCancelled() {
            return false;
        }
    };

    /** @param stage a short English label for the drawer ("Comparing passages", "Merging topics") */
    void progress(String stage, long done, long total);

    boolean isCancelled();

    /** Thrown when {@link #isCancelled()} turned true during a run; nothing is persisted. */
    final class ClusteringCancelledException extends RuntimeException {
        public ClusteringCancelledException() {
            super("Clustering cancelled");
        }
    }
}
