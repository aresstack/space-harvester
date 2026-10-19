package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

/**
 * Callback for the credentials of a Git source, fetched again for every fetch; the composition root bridges it to
 * KeePass. The source never sees the vault itself.
 */
public interface GitCredentialsProvider {

    /**
     * @return fresh credentials or {@code null} for anonymous access (public repositories)
     * @throws Exception if the credentials cannot be obtained (vault locked, entry missing); the source reports
     *                   {@code ACCESS_DENIED} without passing the cause on
     */
    GitCredentials credentials() throws Exception;

    /** Anonymous access. */
    static GitCredentialsProvider none() {
        return new GitCredentialsProvider() {
            @Override
            public GitCredentials credentials() {
                return null;
            }
        };
    }
}
