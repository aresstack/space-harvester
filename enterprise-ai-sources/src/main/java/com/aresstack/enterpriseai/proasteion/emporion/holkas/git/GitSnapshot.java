package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

/** One pinned state of the configured ref: commit, its tree and the ref it came from. Immutable. */
final class GitSnapshot {

    final String commit;
    final String tree;
    /** Full name on the remote ({@code refs/heads/main}) or the configured commit id. */
    final String remoteRef;
    /** Short name for display and links ({@code main}). */
    final String branch;
    final long commitTimeMillis;

    GitSnapshot(String commit, String tree, String remoteRef, String branch, long commitTimeMillis) {
        this.commit = commit;
        this.tree = tree;
        this.remoteRef = remoteRef;
        this.branch = branch;
        this.commitTimeMillis = commitTimeMillis;
    }
}
