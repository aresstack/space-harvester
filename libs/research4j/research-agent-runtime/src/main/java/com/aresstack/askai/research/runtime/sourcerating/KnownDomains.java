package com.aresstack.askai.research.runtime.sourcerating;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Component 16: the list of known domains that may SUGGEST a kind and a jurisdiction (build plan: "Eine Liste
 * bekannter Domains darf vorschlagen, das Modell entscheidet und begründet"). It names public institutions, law
 * databases, reference works, large scientific publishers and social networks of Germany, Austria, Switzerland and
 * the EU, nothing chosen for one book. A host matches its own entry or the entry of a parent domain
 * ({@code forum.dguv.de} matches {@code dguv.de}); the most specific entry wins. Hosts of German universities
 * ({@code uni-*.de}, {@code tu-*.de}, ...), {@code *.edu} and {@code *.ac.at} / {@code *.ac.uk} are suggested as
 * science. Immutable.
 */
public final class KnownDomains {

    /** One suggestion; immutable. */
    public static final class Entry {
        private final String domain;
        private final SourceRating.Kind kind;
        private final SourceRating.Jurisdiction jurisdiction;
        private final String note;

        Entry(String domain, SourceRating.Kind kind, SourceRating.Jurisdiction jurisdiction, String note) {
            this.domain = domain;
            this.kind = kind;
            this.jurisdiction = jurisdiction;
            this.note = note == null ? "" : note;
        }

        /** The listed domain (or rule) that matched. */
        public String getDomain() {
            return domain;
        }

        public SourceRating.Kind getKind() {
            return kind;
        }

        public SourceRating.Jurisdiction getJurisdiction() {
            return jurisdiction;
        }

        /** Who it is, in a few words. */
        public String getNote() {
            return note;
        }

        @Override
        public String toString() {
            return domain + " = " + kind + ", " + jurisdiction + (note.isEmpty() ? "" : " (" + note + ")");
        }
    }

    private static final String RESOURCE = "known-domains.json";
    private static final String[] UNIVERSITY_PREFIXES = {"uni-", "tu-", "th-", "fh-", "hs-"};

    private final Map<String, Entry> byDomain;

    KnownDomains(Map<String, Entry> byDomain) {
        this.byDomain = Collections.unmodifiableMap(new LinkedHashMap<String, Entry>(byDomain));
    }

    /** No suggestions at all. */
    public static KnownDomains none() {
        return new KnownDomains(Collections.<String, Entry>emptyMap());
    }

    /** The list shipped with the app ({@code known-domains.json} next to this class). */
    public static KnownDomains shipped() {
        InputStream in = KnownDomains.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("missing resource " + RESOURCE);
        }
        try (InputStream stream = in) {
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + RESOURCE, ex);
        }
    }

    /** {@code {"domains": [{"domain", "quellenart", "rechtsraum", "hinweis"}, ...]}}. */
    static KnownDomains parse(String json) {
        Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("domains")) {
            JsonObject o = element.getAsJsonObject();
            String domain = SourceSample.hostOf(o.get("domain").getAsString());
            if (domain.isEmpty() || entries.containsKey(domain)) {
                throw new IllegalArgumentException("empty or repeated domain in the known list: " + domain);
            }
            entries.put(domain, new Entry(domain, SourceRating.Kind.valueOf(o.get("quellenart").getAsString()),
                    SourceRating.Jurisdiction.valueOf(o.get("rechtsraum").getAsString()),
                    o.has("hinweis") ? o.get("hinweis").getAsString() : ""));
        }
        return new KnownDomains(entries);
    }

    /** The listed domains, in file order. */
    public List<String> domains() {
        return new ArrayList<String>(byDomain.keySet());
    }

    /** The suggestion for a host (or an address), {@code null} when the list knows it not. */
    public Entry suggest(String hostOrAddress) {
        String host = SourceSample.hostOf(hostOrAddress);
        if (host.isEmpty() || byDomain.isEmpty()) {
            return null;
        }
        for (String candidate = host; candidate.contains("."); candidate = candidate.substring(
                candidate.indexOf('.') + 1)) {
            Entry entry = byDomain.get(candidate);
            if (entry != null) {
                return entry;
            }
        }
        String[] labels = host.split("\\.");
        String last = labels[labels.length - 1];
        if ("edu".equals(last)) {
            return new Entry("*.edu", SourceRating.Kind.WISSENSCHAFT, SourceRating.Jurisdiction.OHNE, "Hochschule");
        }
        if (labels.length >= 3 && "ac".equals(labels[labels.length - 2]) && ("at".equals(last) || "uk".equals(last))) {
            return new Entry("*.ac." + last, SourceRating.Kind.WISSENSCHAFT, "at".equals(last)
                    ? SourceRating.Jurisdiction.AT : SourceRating.Jurisdiction.ANDERE, "Hochschule");
        }
        if ("de".equals(last) && labels.length >= 2) {
            String registrable = labels[labels.length - 2].toLowerCase(Locale.ROOT);
            for (String prefix : UNIVERSITY_PREFIXES) {
                if (registrable.startsWith(prefix)) {
                    return new Entry(prefix + "*.de", SourceRating.Kind.WISSENSCHAFT, SourceRating.Jurisdiction.DE,
                            "Hochschule");
                }
            }
        }
        return null;
    }
}
