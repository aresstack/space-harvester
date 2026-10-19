package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.astu.source.SourceLocators;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * The remote of a Git source as the user entered it, checked for the MVP of #137: {@code https://} (and
 * {@code http://}) remotes such as Azure Repos, GitHub or GitLab, and local repositories ({@code file://} or an
 * absolute path). Credentials never belong to the URL: a user part ({@code https://user:token@host/...}) is refused,
 * so neither the stored settings nor resource ids, metadata, logs or messages can carry a token. SSH
 * ({@code ssh://}, {@code git@host:repo}) and {@code git://} are refused with a clear message instead of a fallback.
 */
final class GitRemote {

    private final String uri;
    private final String locator;
    private final URI web;

    private GitRemote(String uri, String locator, URI web) {
        this.uri = uri;
        this.locator = locator;
        this.web = web;
    }

    /**
     * Parse a remote.
     *
     * @return the remote, never {@code null}
     * @throws IllegalArgumentException with a message for the user (without the value) if the remote is not supported
     */
    static GitRemote parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("fehlt (Pflichtangabe: Adresse des Git-Repositorys)");
        }
        String text = value.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ssh://") || lower.startsWith("git://") || lower.startsWith("git+ssh://")
                || (!lower.contains("://") && text.matches("^[^/\\\\]+@[^/\\\\:]+:.*"))) {
            throw new IllegalArgumentException("SSH und git:// werden noch nicht unterstützt; bitte die HTTPS-Adresse "
                    + "des Repositorys angeben");
        }
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            URI uri;
            try {
                uri = new URI(text);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("keine gültige URL");
            }
            if (uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
                throw new IllegalArgumentException("ohne Benutzer und Token in der Adresse; Zugangsdaten gehören in "
                        + "den KeePass-Eintrag");
            }
            if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Adresse braucht einen Host und darf keine Parameter (?…, #…) haben");
            }
            String canonical = stripSlashes(text);
            return new GitRemote(canonical, stripGitSuffix(SourceLocators.url(canonical)), web(uri));
        }
        if (lower.contains("://") && !lower.startsWith("file://")) {
            throw new IllegalArgumentException("nur HTTPS, HTTP oder ein lokales Repository (Pfad oder file-URL)");
        }
        Path path;
        try {
            path = lower.startsWith("file://") ? Paths.get(new URI(text)) : Paths.get(text);
        } catch (URISyntaxException | RuntimeException e) {
            throw new IllegalArgumentException("kein gültiger Pfad zu einem lokalen Repository");
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("lokale Repositorys mit absolutem Pfad angeben");
        }
        Path normalized = path.normalize();
        return new GitRemote(stripSlashes(normalized.toUri().toString()),
                stripGitSuffix(SourceLocators.path(normalized.toString()).toLowerCase(Locale.ROOT)), null);
    }

    /** The remote for JGit; never carries credentials. */
    String uri() {
        return uri;
    }

    /** Repository identity without scheme, e.g. {@code dev.azure.com/org/project/_git/repo}. */
    String locator() {
        return locator;
    }

    /** Whether the remote is reached over HTTP(S). */
    boolean isHttp() {
        return web != null || uri.toLowerCase(Locale.ROOT).startsWith("http");
    }

    /**
     * The web page of a file, only where the host's layout is known for sure (GitHub, Azure Repos); otherwise
     * {@code null}.
     */
    URI browserUrl(String path, String branch, String commit) {
        if (web == null) {
            return null;
        }
        try {
            String host = web.getHost().toLowerCase(Locale.ROOT);
            String repo = stripGitSuffix(stripSlashes(web.getRawPath()));
            if ("github.com".equals(host) && commit != null) {
                return new URI("https", "github.com", repo + "/blob/" + commit + "/" + path, null);
            }
            if ("dev.azure.com".equals(host) && repo.contains("/_git/")) {
                String version = commit != null ? "GC" + commit : "GB" + branch;
                return new URI("https", "dev.azure.com", repo, "path=/" + path + "&version=" + version, null);
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return null;
    }

    @Override
    public String toString() {
        return uri;
    }

    private static URI web(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return "https".equals(scheme) ? uri : null;
    }

    private static String stripSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stripGitSuffix(String value) {
        return value.toLowerCase(Locale.ROOT).endsWith(".git") ? value.substring(0, value.length() - 4) : value;
    }
}
