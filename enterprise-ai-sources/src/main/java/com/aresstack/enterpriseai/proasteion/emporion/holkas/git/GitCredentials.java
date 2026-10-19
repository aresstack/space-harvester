package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import java.util.Arrays;

/**
 * Read access to an HTTPS Git remote: user name and personal access token (Azure Repos: any user name with a PAT
 * of scope „Code (Read)“; GitHub/GitLab: user and token). Lives only for one fetch; {@link #clear()} overwrites it.
 */
public final class GitCredentials {

    private final String username;
    private final char[] token;

    /**
     * @param username user name; blank = {@code git} (Azure Repos and GitHub ignore it for tokens)
     * @param token    access token or password; copied
     */
    public GitCredentials(String username, char[] token) {
        if (token == null || token.length == 0) {
            throw new IllegalArgumentException("token must not be empty");
        }
        this.username = username == null || username.trim().isEmpty() ? "git" : username.trim();
        this.token = token.clone();
    }

    public String username() {
        return username;
    }

    char[] token() {
        return token;
    }

    /** Overwrite the token in memory. */
    public void clear() {
        Arrays.fill(token, '\0');
    }

    @Override
    public String toString() {
        return "GitCredentials{username=" + username + ", token=***}";
    }
}
