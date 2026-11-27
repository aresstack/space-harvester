package com.aresstack.askai.agent.model;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * The bearer token for an endpoint an agent calls directly (an OpenAI-compatible API's {@code /embeddings} or
 * {@code /rerank}): the host installs one {@link Source} that knows the configured API and its key (stored encrypted
 * or read from KeePass); descriptors stay secret-free. The token is handed out per request and never kept here. Any
 * URL outside the configured API gets {@code null}, so a key never reaches Ollama or another server. The source also
 * opens the connections, so API requests use the host's network settings (proxy, certificate trust).
 */
public final class EndpointAuthorization {

    /** Said instead of the JDK's refusal of a header value, which quotes the whole value (the key). */
    public static final String KEY_WITH_CONTROL_CHARACTERS =
            "the API key contains a line break or another control character; enter it again";

    /** Gives the token for a request URL, or {@code null} when the URL does not belong to the configured API. */
    public interface Source {
        char[] bearerFor(String url) throws IOException;

        /** Opens a connection to {@code url}; the host's network settings apply where the host wants them. */
        default HttpURLConnection open(String url) throws IOException {
            return plain(url);
        }
    }

    private static volatile Source source;

    private EndpointAuthorization() {
    }

    /** Installed by the host at start; {@code null} removes it. */
    public static void install(Source value) {
        source = value;
    }

    /** The token for {@code url} (the caller clears it), {@code null} when none applies. */
    public static char[] bearerFor(String url) throws IOException {
        Source current = source;
        return current == null || url == null ? null : current.bearerFor(url);
    }

    /** A connection to {@code url} through the host's source (its network settings), plain without one. */
    public static HttpURLConnection open(String url) throws IOException {
        Source current = source;
        return current == null ? plain(url) : current.open(url);
    }

    /**
     * Sets {@code Authorization: Bearer} from {@code bearer} and clears it. A key with a control character is
     * refused with {@link #KEY_WITH_CONTROL_CHARACTERS}, never with a message that holds the key.
     *
     * @return whether a header was set (an empty key sets none)
     */
    public static boolean authorize(HttpURLConnection connection, char[] bearer) throws IOException {
        if (bearer == null) {
            return false;
        }
        try {
            int start = 0;
            int end = bearer.length;
            while (start < end && bearer[start] == ' ') {
                start++;
            }
            while (end > start && bearer[end - 1] == ' ') {
                end--;
            }
            for (int i = start; i < end; i++) {
                char c = bearer[i];
                if (c < 0x20 || c == 0x7f) {
                    throw new IOException(KEY_WITH_CONTROL_CHARACTERS);
                }
            }
            if (start == end) {
                return false;
            }
            try {
                connection.setRequestProperty("Authorization", "Bearer " + new String(bearer, start, end - start));
            } catch (IllegalArgumentException refused) {
                throw new IOException(KEY_WITH_CONTROL_CHARACTERS);
            }
            return true;
        } finally {
            Arrays.fill(bearer, '\0');
        }
    }

    private static HttpURLConnection plain(String url) throws IOException {
        return (HttpURLConnection) new URL(url).openConnection();
    }
}
