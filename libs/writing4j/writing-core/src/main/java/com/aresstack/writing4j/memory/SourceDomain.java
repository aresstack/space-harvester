package com.aresstack.writing4j.memory;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Component 7: the registrable domain of a source address, for the rule "two sources count apart only when domain or
 * text differ". {@code https://www.bmas.de/x} and {@code https://bmas.de/print/x} are both {@code bmas.de};
 * {@code https://ico.org.uk/x} is {@code ico.org.uk}, {@code https://www.ris.bka.gv.at/x} is {@code bka.gv.at}.
 * Without a public suffix list the rule is: the last two labels, or the last three when the second-to-last label is
 * a common second-level label under a two-letter country ({@code co.uk}, {@code com.au}, {@code gv.at}, ...) or the
 * domain is a portal whose sub-domain is the authority ({@code bund.de}, the German states, {@code europa.eu},
 * {@code admin.ch}).
 */
public final class SourceDomain {

    private static final Set<String> SECOND_LEVEL = new HashSet<String>(Arrays.asList(
            "co", "com", "org", "net", "gov", "gv", "ac", "edu", "or", "ne", "go", "gob", "nic", "mil"));

    /**
     * Portals whose sub-domain is the authority (architect review of 21.11.: bsi.bund.de and bmas.bund.de, ldi.nrw.de
     * and arbeitsschutz.nrw.de, osha.europa.eu and eur-lex.europa.eu are different publishers): three labels stay.
     */
    private static final Set<String> PORTALS = new HashSet<String>(Arrays.asList(
            "bund.de", "baden-wuerttemberg.de", "bayern.de", "berlin.de", "brandenburg.de", "bremen.de", "hamburg.de",
            "hessen.de", "mv-regierung.de", "regierung-mv.de", "niedersachsen.de", "nrw.de", "rlp.de", "saarland.de",
            "sachsen.de", "sachsen-anhalt.de", "schleswig-holstein.de", "thueringen.de", "europa.eu", "admin.ch"));

    private SourceDomain() {
    }

    /** The registrable domain, lower case, {@code ""} when the address names no host. */
    public static String of(String address) {
        String url = Texts.clean(address);
        if (url.isEmpty()) {
            return "";
        }
        String host;
        try {
            host = new URI(url.contains("://") ? url : "https://" + url).getHost();
        } catch (Exception notAnAddress) {
            return "";
        }
        if (host == null) {
            return "";
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.matches("[0-9.]+") || host.contains(":")) {
            return host;
        }
        host = host.replaceFirst("^www\\d*\\.", "");
        String[] labels = host.split("\\.");
        if (labels.length <= 2) {
            return host;
        }
        String lastTwo = labels[labels.length - 2] + "." + labels[labels.length - 1];
        int keep = PORTALS.contains(lastTwo)
                || labels[labels.length - 1].length() == 2 && SECOND_LEVEL.contains(labels[labels.length - 2])
                ? 3 : 2;
        StringBuilder domain = new StringBuilder();
        for (int i = labels.length - keep; i < labels.length; i++) {
            if (domain.length() > 0) {
                domain.append('.');
            }
            domain.append(labels[i]);
        }
        return domain.toString();
    }
}
