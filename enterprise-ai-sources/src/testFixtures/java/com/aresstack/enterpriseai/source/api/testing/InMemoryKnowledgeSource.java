package com.aresstack.enterpriseai.source.api.testing;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fake-Wissensquelle im Speicher, für Use-Case-Tests ohne Netz (z. B. {@code IndexKnowledgeUseCase} in AP10)
 * und als Referenzimplementierung des Vertrags.
 *
 * <p>Ressourcen werden über einen quellenlokalen Schlüssel angelegt; Startpunkte im {@link SourceScope} sind
 * diese Schlüssel. Discovery folgt Links und Kindressourcen ({@link #addChild}). IDs haben die Form {@code <scheme>:<sourceId>/<key>}. {@link #update} erhöht die Revision,
 * {@link #remove} entfernt eine Ressource, {@link #failWith} lässt Aufrufe für eine Ressource scheitern –
 * damit lassen sich Neuindexierung, Löschung und Fehlerpfade testen. Alle Aufrufe werden in
 * {@link #calls()} protokolliert.
 */
public final class InMemoryKnowledgeSource implements SearchableKnowledgeSource {

    private final SourceAddress sourceId;
    private final String scheme;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private final Map<String, Kind> failures = new HashMap<String, Kind>();
    private final List<String> calls = new ArrayList<String>();

    public InMemoryKnowledgeSource(String sourceId) {
        this(sourceId, "memory");
    }

    public InMemoryKnowledgeSource(String sourceId, String scheme) {
        this.sourceId = SourceAddress.of(sourceId);
        this.scheme = scheme;
    }

    /** Legt eine Ressource an (Revision 1) oder ersetzt sie; {@code links} sind Schlüssel anderer Ressourcen. */
    public synchronized InMemoryKnowledgeSource add(String key, String title, String text, String... links) {
        Entry previous = entries.get(key);
        long revision = previous == null ? 1 : previous.revision + 1;
        entries.put(key, new Entry(key, title, text, revision, Arrays.asList(links), previous == null ? null
                : previous.parentKey));
        return this;
    }

    /** Wie {@link #add}, mit Elternressource (Scope-Hierarchie wie Confluence-Kindseiten). */
    public synchronized InMemoryKnowledgeSource addChild(String parentKey, String key, String title, String text,
                                                         String... links) {
        add(key, title, text, links);
        Entry entry = entries.get(key);
        entries.put(key, new Entry(key, title, text, entry.revision, entry.links, parentKey));
        return this;
    }

    /** Ändert den Text einer vorhandenen Ressource und erhöht ihre Revision. */
    public synchronized InMemoryKnowledgeSource update(String key, String text) {
        Entry entry = require(key);
        entries.put(key, new Entry(key, entry.title, text, entry.revision + 1, entry.links, entry.parentKey));
        return this;
    }

    public synchronized InMemoryKnowledgeSource remove(String key) {
        entries.remove(key);
        return this;
    }

    /** Alle Aufrufe, die {@code key} betreffen, scheitern mit der angegebenen Fehlerart. */
    public synchronized InMemoryKnowledgeSource failWith(String key, Kind kind) {
        failures.put(key, kind);
        return this;
    }

    public synchronized InMemoryKnowledgeSource clearFailures() {
        failures.clear();
        return this;
    }

    public KnowledgeResourceId idOf(String key) {
        return KnowledgeResourceId.of(scheme, sourceId.value() + "/" + key);
    }

    /** Protokoll der Port-Aufrufe, z. B. {@code "load:a"}, {@code "discover:[a]"}. */
    public synchronized List<String> calls() {
        return new ArrayList<String>(calls);
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public synchronized List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        calls.add("discover:" + scope.startPoints());
        Set<String> visited = new LinkedHashSet<String>();
        List<String> level = new ArrayList<String>(scope.startPoints());
        for (int depth = 0; depth <= scope.maxDepth() && !level.isEmpty(); depth++) {
            List<String> next = new ArrayList<String>();
            for (String key : level) {
                if (visited.size() >= scope.maxResources()) {
                    break;
                }
                if (!entries.containsKey(key) || visited.contains(key)) {
                    continue;
                }
                fail(key);
                visited.add(key);
                next.addAll(entries.get(key).links);
                next.addAll(childrenOf(key));
            }
            level = next;
        }
        List<KnowledgeResource> result = new ArrayList<KnowledgeResource>();
        for (String key : visited) {
            result.add(resource(entries.get(key)));
        }
        return result;
    }

    @Override
    public synchronized KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String key = keyOf(resourceId);
        calls.add("load:" + key);
        fail(key);
        Entry entry = existing(key, resourceId);
        return KnowledgeDocument.of(resource(entry), entry.text);
    }

    @Override
    public synchronized List<SourceLink> discoverLinks(KnowledgeResourceId resourceId)
            throws KnowledgeSourceException {
        String key = keyOf(resourceId);
        calls.add("links:" + key);
        fail(key);
        Entry entry = existing(key, resourceId);
        List<SourceLink> links = new ArrayList<SourceLink>();
        Set<String> targets = new LinkedHashSet<String>(entry.links);
        targets.addAll(childrenOf(key));
        for (String target : targets) {
            Entry linked = entries.get(target);
            links.add(new SourceLink(idOf(target), linked == null ? target : linked.title));
        }
        return links;
    }

    @Override
    public synchronized List<SourceSearchHit> search(SourceQuery query) throws KnowledgeSourceException {
        calls.add("search:" + query.text());
        List<SourceSearchHit> hits = new ArrayList<SourceSearchHit>();
        for (Entry entry : entries.values()) {
            if (hits.size() >= query.limit()) {
                break;
            }
            int at = indexOfIgnoreCase(entry.text, query.text());
            if (at >= 0 || indexOfIgnoreCase(entry.title, query.text()) >= 0) {
                String snippet = at < 0 ? "" : entry.text.substring(Math.max(0, at - 20),
                        Math.min(entry.text.length(), at + query.text().length() + 20));
                hits.add(new SourceSearchHit(idOf(entry.key), entry.title, snippet));
            }
        }
        return hits;
    }

    /** Fundstelle im Originaltext (Indizes bleiben gültig, anders als nach {@code toLowerCase}). */
    private static int indexOfIgnoreCase(String text, String needle) {
        for (int i = 0; i + needle.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Kindressourcen (über {@link #addChild}) zählen wie Links, analog zu Confluence-Kindseiten. */
    private List<String> childrenOf(String parentKey) {
        List<String> children = new ArrayList<String>();
        for (Entry candidate : entries.values()) {
            if (parentKey.equals(candidate.parentKey)) {
                children.add(candidate.key);
            }
        }
        return children;
    }

    private KnowledgeResource resource(Entry entry) {
        return KnowledgeResource.builder(idOf(entry.key), sourceId)
                .title(entry.title)
                .contentType("text/markdown")
                .revision(KnowledgeRevision.of(Instant.ofEpochSecond(1700000000L + entry.revision),
                        String.valueOf(entry.revision)))
                .parentId(entry.parentKey == null ? null : idOf(entry.parentKey))
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(Collections.singletonMap("key", entry.key)))
                .build();
    }

    private String keyOf(KnowledgeResourceId id) throws KnowledgeSourceException {
        String prefix = sourceId.value() + "/";
        if (!scheme.equals(id.scheme()) || !id.schemeSpecificPart().startsWith(prefix)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, id + " does not belong to source " + sourceId);
        }
        return id.schemeSpecificPart().substring(prefix.length());
    }

    private Entry existing(String key, KnowledgeResourceId id) throws KnowledgeSourceException {
        Entry entry = entries.get(key);
        if (entry == null) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, id + " not found");
        }
        return entry;
    }

    private Entry require(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("unknown key " + key);
        }
        return entry;
    }

    private void fail(String key) throws KnowledgeSourceException {
        Kind kind = failures.get(key);
        if (kind != null) {
            throw new KnowledgeSourceException(kind, "simulated failure for " + key);
        }
    }

    private static final class Entry {

        final String key;
        final String title;
        final String text;
        final long revision;
        final List<String> links;
        final String parentKey;

        Entry(String key, String title, String text, long revision, List<String> links, String parentKey) {
            this.key = key;
            this.title = title;
            this.text = text;
            this.revision = revision;
            this.links = Collections.unmodifiableList(new ArrayList<String>(links));
            this.parentKey = parentKey;
        }
    }
}
