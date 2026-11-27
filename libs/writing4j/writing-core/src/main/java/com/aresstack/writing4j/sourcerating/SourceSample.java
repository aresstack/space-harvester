package com.aresstack.writing4j.sourcerating;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * What the source rating reads of one source (build plan, component 16): address, title, host, the first passage and
 * the headings of its passages (the heading path each passage already carries, without a model). Immutable.
 */
public final class SourceSample {

    private final String sourceId;
    private final String address;
    private final String title;
    private final String host;
    private final String firstPassage;
    private final List<String> headings;

    /**
     * @param sourceId     the source's id in the project
     * @param address      its address (URL)
     * @param title        its title, may be empty
     * @param host         its host; empty to take it from the address
     * @param firstPassage the text of its first passage, may be empty
     */
    public SourceSample(String sourceId, String address, String title, String host, String firstPassage) {
        this(sourceId, address, title, host, firstPassage, null);
    }

    /**
     * @param headings the heading paths of the source's passages in their order; repeats and empty ones are dropped
     */
    public SourceSample(String sourceId, String address, String title, String host, String firstPassage,
                        List<String> headings) {
        LinkedHashSet<String> distinct = new LinkedHashSet<String>();
        if (headings != null) {
            for (String heading : headings) {
                String h = heading == null ? "" : heading.replaceAll("\\s+", " ").trim();
                if (!h.isEmpty()) {
                    distinct.add(h);
                }
            }
        }
        this.headings = Collections.unmodifiableList(new ArrayList<String>(distinct));
        this.sourceId = sourceId == null ? "" : sourceId.trim();
        this.address = address == null ? "" : address.trim();
        this.title = title == null ? "" : title.trim();
        String given = host == null ? "" : host.trim();
        this.host = hostOf(given.isEmpty() ? this.address : given);
        this.firstPassage = firstPassage == null ? "" : firstPassage;
    }

    /** The distinct heading paths of the source's passages, in order; empty when the source shows none. */
    public List<String> getHeadings() {
        return headings;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getAddress() {
        return address;
    }

    public String getTitle() {
        return title;
    }

    /** The source group: lower case, without {@code www.}; empty when the address names no host. */
    public String getHost() {
        return host;
    }

    public String getFirstPassage() {
        return firstPassage;
    }

    /** The host of an address or a bare host name, lower case, without a leading {@code www.} and a closing dot. */
    public static String hostOf(String addressOrHost) {
        String rest = (addressOrHost == null ? "" : addressOrHost.trim()).replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://",
                "");
        int cut = rest.length();
        for (char stop : new char[]{'/', '?', '#', ' '}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        String host = rest.substring(0, cut);
        host = host.substring(host.lastIndexOf('@') + 1);
        int port = host.indexOf(':');
        if (port >= 0) {
            host = host.substring(0, port);
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.replaceFirst("^www\\d*\\.", "");
    }

    @Override
    public String toString() {
        return sourceId + " " + address;
    }
}
