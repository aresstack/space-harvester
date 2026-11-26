package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.ftp.CobolInventory;
import com.aresstack.spaceharvester.deck.ftp.MvsPath;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Build a bounded, session-local facts snapshot from an explicitly inventoried area. */
final class CobolSearchAcquisition {
    static final int MAX_PROGRAMS = 500;
    static final int MAX_SOURCE_CHARS = 1_000_000;
    static final int MAX_FACTS = 200_000;

    record Snapshot(CobolSearch search, String scope, int candidates, int inspected, int programs,
                    int partial, int unclassified, int failed, boolean limited) {
        String coverage() {
            return scope + " · " + inspected + "/" + candidates + " Member geprüft · " + programs
                    + " COBOL-Programme · " + partial + " Teilanalysen · " + unclassified
                    + " nicht als COBOL-Programm klassifiziert · " + failed + " Fehler"
                    + (limited ? " · Analysegrenze erreicht" : "")
                    + " · Momentaufnahme, keine Live-Vollständigkeitsgarantie";
        }
    }

    private final DocumentStore store;
    private final CobolAnalyzer analyzer;

    CobolSearchAcquisition(DocumentStore store) {
        this.store = store;
        this.analyzer = CobolAnalyzer.proLeap();
    }

    Snapshot build(CobolInventory.Result inventory, BooleanSupplier cancelled, Consumer<String> progress) {
        if (!inventory.complete()) {
            throw new IllegalArgumentException("Zuerst eine vollständige Inventur durchführen.");
        }
        List<CobolSearch.Fact> facts = new ArrayList<>();
        List<String> programs = inventory.members().stream().filter(CobolInventory.Member::programCandidate)
                .map(CobolInventory.Member::path).distinct().toList();
        boolean limited = false;
        for (var member : inventory.members()) {
            check(cancelled);
            if (!member.copybookCandidate()) continue;
            if (facts.size() >= MAX_FACTS) {
                limited = true;
                break;
            }
            facts.add(new CobolSearch.Fact(CobolSearch.Kind.COPYBOOK_CANDIDATE,
                    MvsPath.splitMember(member.path())[1], "", "", member.path(), 0, "",
                    Map.of("STATUS", "SYSLIB-Member, nicht einzeln klassifiziert"), false));
        }
        Resolver resolver = new Resolver(inventory, cancelled);
        int inspected = 0, classified = 0, partial = 0, unclassified = 0, failed = 0;
        for (String path : programs) {
            check(cancelled);
            if (limited || inspected >= MAX_PROGRAMS) {
                limited = true;
                break;
            }
            progress.accept("COBOL-Analyse " + (inspected + 1) + "/" + programs.size() + " · " + path);
            inspected++;
            try {
                EditorDocument document = load(path, cancelled);
                resolver.beginProgram();
                var analysis = analyzer.analyze(CobolSource.fixed(path, document.text()), resolver);
                check(cancelled);
                List<CobolSearch.Fact> memberFacts = CobolSearch.programFacts(path, document.hash(), analysis);
                if (memberFacts.isEmpty()) {
                    unclassified++;
                    continue;
                }
                if (facts.size() + memberFacts.size() > MAX_FACTS) {
                    limited = true;
                    break;
                }
                memberFacts.stream().map(fact -> withSourceRevision(fact, resolver, document)).forEach(facts::add);
                classified++;
                if (analysis.isPartial()) partial++;
            } catch (IOException error) {
                failed++;
            }
        }
        check(cancelled);
        String scope = inventory.area().absoluteHome() + " · SYSLIB " + inventory.area().syslib();
        return new Snapshot(new CobolSearch(facts), scope, programs.size(), inspected, classified,
                partial, unclassified, failed, limited);
    }

    private CobolSearch.Fact withSourceRevision(CobolSearch.Fact fact, Resolver resolver, EditorDocument document) {
        String revision = key(fact.source()).equals(key(document.location())) ? document.hash()
                : resolver.observations.getOrDefault(key(fact.source()), "");
        Map<String, String> attributes = new LinkedHashMap<>(fact.attributes());
        if (!revision.isEmpty()) attributes.put("SOURCE_REVISION", revision);
        return new CobolSearch.Fact(fact.kind(), fact.name(), fact.program(), fact.member(),
                fact.source(), fact.line(), fact.revision(), attributes, fact.partial());
    }

    private EditorDocument load(String path, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        try {
            EditorDocument document = store.load(path);
            check(cancelled);
            if (!key(path).equals(key(document.location()))) {
                throw new IOException("Member-Identität weicht ab: " + path);
            }
            if (document.text().length() > MAX_SOURCE_CHARS) {
                throw new IOException("Quelltext überschreitet Analysegrenze: " + path);
            }
            return document;
        } catch (com.aresstack.spaceharvester.deck.editor.DocumentException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Analyse abgebrochen");
        }
    }

    private static String key(String path) {
        return MvsPath.unquote(path).toUpperCase(Locale.ROOT);
    }

    private final class Resolver implements CopybookResolver {
        private static final int CACHE_CHARS = 8_000_000;
        private final Set<String> members = new LinkedHashSet<>();
        private final List<String> syslib;
        private final BooleanSupplier cancelled;
        private record Loaded(CobolSource source, String revision) { }
        private final Map<String, Loaded> cached = new LinkedHashMap<>();
        private final Map<String, String> observations = new LinkedHashMap<>();
        private int chars;

        Resolver(CobolInventory.Result inventory, BooleanSupplier cancelled) {
            inventory.members().forEach(member -> members.add(key(member.path())));
            syslib = inventory.area().syslib();
            this.cancelled = cancelled;
        }

        void beginProgram() {
            observations.clear();
        }

        @Override
        public Optional<CobolSource> resolve(CobolSource including, String name, String library) throws IOException {
            check(cancelled);
            if (!MvsPath.isMemberName(name)) throw new IOException("Ungültiger COPY-Name");
            Set<String> datasets = new LinkedHashSet<>();
            if (library.isBlank()) {
                datasets.addAll(syslib);
                datasets.add(MvsPath.splitMember(including.name())[0]);
            } else {
                String qualified = key(library);
                syslib.stream().filter(ds -> key(ds).equals(qualified)).forEach(datasets::add);
                if (datasets.isEmpty()) {
                    syslib.stream().filter(ds -> key(ds).endsWith("." + qualified)).forEach(datasets::add);
                }
                if (datasets.size() != 1) throw new IOException("Keine eindeutige SYSLIB: " + library);
            }
            for (String dataset : datasets) {
                String candidate = key(MvsPath.memberSpec(MvsPath.unquote(dataset), name));
                if (members.contains(candidate)) {
                    return Optional.of(copybook(candidate, including.format()));
                }
            }
            return Optional.empty();
        }

        private CobolSource copybook(String candidate, CobolSource.Format format) throws IOException {
            Loaded existing = cached.get(candidate);
            if (existing != null) {
                observations.put(candidate, existing.revision());
                return existing.source();
            }
            String path = MvsPath.absolute(candidate);
            EditorDocument document = load(path, cancelled);
            String prior = observations.putIfAbsent(candidate, document.hash());
            if (prior != null && !prior.equals(document.hash())) {
                throw new IOException("Copybook während der Analyse geändert: " + path);
            }
            CobolSource source = new CobolSource(path, document.text(), format);
            while (!cached.isEmpty() && chars + source.content().length() > CACHE_CHARS) {
                String first = cached.keySet().iterator().next();
                chars -= cached.remove(first).source().content().length();
            }
            cached.put(candidate, new Loaded(source, document.hash()));
            chars += source.content().length();
            return source;
        }
    }
}
