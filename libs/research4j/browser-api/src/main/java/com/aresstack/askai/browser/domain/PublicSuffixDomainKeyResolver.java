package com.aresstack.askai.browser.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The productive {@link DomainKeyResolver}: registrable domains are computed with the full Public Suffix
 * List algorithm against a BUNDLED, versioned snapshot of publicsuffix.org loaded once from the classpath
 * resource {@code domain/public-suffix-snapshot.txt}. The snapshot carries both the ICANN and the PRIVATE
 * section — so {@code foo.github.io} and {@code bar.github.io} are DISTINCT registrable domains and a
 * challenge on one hosted tenant never blocks unrelated tenants of the same platform. Wildcard rules
 * ({@code *.kawasaki.jp}) and exception rules ({@code !city.kawasaki.jp}) are honoured; unicode rules are
 * additionally indexed in their IDN/punycode form. The registrable domain is the prevailing public suffix
 * plus one label; a host that IS a public suffix keeps itself as registrable domain. The snapshot is NEVER
 * fetched from the network during a research session and is refreshed by replacing the bundled resource.
 */
public final class PublicSuffixDomainKeyResolver implements DomainKeyResolver {

    private static final String SNAPSHOT_RESOURCE = "domain/public-suffix-snapshot.txt";

    /** Exact rules ({@code co.uk}, {@code github.io}, plain TLDs). */
    private final Set<String> exactRules;
    /** The part after {@code *.} of wildcard rules ({@code *.kawasaki.jp} → {@code kawasaki.jp}). */
    private final Set<String> wildcardRules;
    /** Exception rules without the {@code !} ({@code !city.kawasaki.jp} → {@code city.kawasaki.jp}). */
    private final Set<String> exceptionRules;
    /** The {@code // VERSION: …} line of the bundled snapshot, or empty if absent. */
    private final String snapshotVersion;

    public PublicSuffixDomainKeyResolver() {
        Snapshot snapshot = loadSnapshot();
        this.exactRules = snapshot.exact;
        this.wildcardRules = snapshot.wildcard;
        this.exceptionRules = snapshot.exception;
        this.snapshotVersion = snapshot.version;
    }

    /** The version stamp of the bundled Public Suffix List snapshot (for diagnostics), or empty. */
    public String getSnapshotVersion() {
        return snapshotVersion;
    }

    @Override
    public DomainIdentity resolve(String urlOrHost) {
        String host = hostOf(urlOrHost);
        if (host.isEmpty()) {
            return new DomainIdentity("", "", HostKind.SINGLE_LABEL_HOST);
        }
        if (isIpLiteral(host)) {
            return new DomainIdentity(host, host, HostKind.IP_LITERAL);
        }
        if ("localhost".equals(host)) {
            return new DomainIdentity(host, host, HostKind.LOCAL_HOST);
        }
        if (host.indexOf('.') < 0) {
            return new DomainIdentity(host, host, HostKind.SINGLE_LABEL_HOST);
        }
        return new DomainIdentity(host, registrableDomainOf(host), HostKind.REGISTERED_NAME);
    }

    /** Prevailing public suffix + one label; a host that is itself a public suffix stays as-is. */
    private String registrableDomainOf(String host) {
        String[] labels = host.split("\\.");
        int suffixLabels = publicSuffixLabelCount(labels);
        if (suffixLabels >= labels.length) {
            return host;
        }
        return join(labels, labels.length - suffixLabels - 1);
    }

    /**
     * PSL algorithm: the prevailing rule is the matching exception rule if any (its suffix is the rule
     * minus the leftmost label), otherwise the matching rule with the most labels; with no match the
     * default rule {@code *} makes the last label the public suffix.
     */
    private int publicSuffixLabelCount(String[] labels) {
        for (int i = 0; i < labels.length; i++) {
            if (exceptionRules.contains(join(labels, i))) {
                return labels.length - i - 1;
            }
        }
        int best = 1;
        for (int i = 0; i < labels.length; i++) {
            int candidateLabels = labels.length - i;
            if (candidateLabels <= best) {
                break; // shorter candidates cannot win anymore
            }
            if (exactRules.contains(join(labels, i))
                    || (i + 1 < labels.length && wildcardRules.contains(join(labels, i + 1)))) {
                best = candidateLabels;
            }
        }
        return best;
    }

    private static String join(String[] labels, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < labels.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    /** Extract the bare host (no scheme, no port, no path) from a URL or return the input as host. */
    public static String hostOf(String urlOrHost) {
        String value = urlOrHost == null ? "" : urlOrHost.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
        }
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (value.startsWith("[")) { // IPv6 literal with optional port: [::1]:8080
            int end = value.indexOf(']');
            return end > 0 ? value.substring(0, end + 1) : value;
        }
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(0, colon) : value;
    }

    private static boolean isIpLiteral(String host) {
        if (host.startsWith("[") || host.matches("[0-9a-f:]*:[0-9a-f:]*")) {
            return true; // IPv6
        }
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static final class Snapshot {
        final Set<String> exact = new HashSet<String>();
        final Set<String> wildcard = new HashSet<String>();
        final Set<String> exception = new HashSet<String>();
        String version = "";
    }

    private static Snapshot loadSnapshot() {
        Snapshot snapshot = new Snapshot();
        InputStream in = PublicSuffixDomainKeyResolver.class.getClassLoader()
                .getResourceAsStream(SNAPSHOT_RESOURCE);
        if (in == null) {
            return snapshot; // missing snapshot degrades to the default last-label rule, never crashes
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
                    if (snapshot.version.isEmpty() && trimmed.contains("VERSION:")) {
                        snapshot.version = trimmed.substring(
                                trimmed.indexOf("VERSION:") + "VERSION:".length()).trim();
                    }
                    continue;
                }
                // PSL semantics: only the part up to the first whitespace is the rule.
                int space = trimmed.indexOf(' ');
                String rule = (space > 0 ? trimmed.substring(0, space) : trimmed)
                        .toLowerCase(Locale.ROOT);
                if (rule.startsWith("!")) {
                    addWithIdnVariant(snapshot.exception, rule.substring(1));
                } else if (rule.startsWith("*.")) {
                    addWithIdnVariant(snapshot.wildcard, rule.substring(2));
                } else {
                    addWithIdnVariant(snapshot.exact, rule);
                }
            }
        } catch (IOException ignored) {
            // partial snapshot is still usable
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return snapshot;
    }

    /** Index unicode rules additionally under their punycode form — hosts from URLs arrive as ASCII. */
    private static void addWithIdnVariant(Set<String> rules, String rule) {
        if (rule.isEmpty()) {
            return;
        }
        rules.add(rule);
        try {
            String ascii = IDN.toASCII(rule).toLowerCase(Locale.ROOT);
            if (!ascii.equals(rule)) {
                rules.add(ascii);
            }
        } catch (RuntimeException ignored) {
            // a rule that IDN cannot encode simply stays unicode-only
        }
    }
}
