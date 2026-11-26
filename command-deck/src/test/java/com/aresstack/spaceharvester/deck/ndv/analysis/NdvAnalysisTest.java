package com.aresstack.spaceharvester.deck.ndv.analysis;

import com.aresstack.spaceharvester.mainframe.dependency.NaturalDependencyService;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.ndv.FakeNdvServer;
import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import com.aresstack.spaceharvester.deck.ndv.cache.FileNdvSourceStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NDV-Analyse wie in MainframeMate: Zwischenspeicher, Vorabladen, Abhängigkeiten, Sperrliste, Suchreihenfolge. */
class NdvAnalysisTest {

    private static final String PROGRAM = """
            * Lagerbestand (erfundenes Beispiel)
            DEFINE DATA
            LOCAL USING LAGERL01
            LOCAL
            1 ART VIEW OF ARTIKEL
            END-DEFINE
            INCLUDE LAGERC01
            READ ART BY NUMMER
            END-READ
            CALLNAT 'LAGERN01' #ARTIKEL
            PERFORM PRUEFEN
            FETCH 'MENU'
            END""";

    private final FakeNdvServer server = FakeNdvServer.sample();
    private final NdvSessions sessions = new NdvSessions(server, Runnable::run);
    private final NdvSession session = sessions.open(new NdvProfile("10.0.0.7", 8011), "",
            () -> new com.aresstack.spaceharvester.deck.access.ServerCredentials(
                    new com.aresstack.spaceharvester.deck.access.ServerAccount("Host", "10.0.0.7", "PILOT"),
                    "geheim".toCharArray()));

    @Test
    void dependenciesAreFoundLikeNaturalOneActiveXRefs() {
        NaturalDependencyService.DependencyResult result = new NaturalDependencyService().analyze(PROGRAM, "LAGER001");
        Map<NaturalDependencyService.DependencyKind, List<String>> found = new HashMap<>();
        result.getGrouped().forEach((kind, deps) -> found.put(kind,
                deps.stream().map(NaturalDependencyService.Dependency::getTargetName).toList()));
        assertThat(found.get(NaturalDependencyService.DependencyKind.USING)).containsExactly("LAGERL01");
        assertThat(found.get(NaturalDependencyService.DependencyKind.VIEW)).containsExactly("ARTIKEL");
        assertThat(found.get(NaturalDependencyService.DependencyKind.INCLUDE)).containsExactly("LAGERC01");
        assertThat(found.get(NaturalDependencyService.DependencyKind.CALLNAT)).containsExactly("LAGERN01");
        assertThat(found.get(NaturalDependencyService.DependencyKind.PERFORM)).containsExactly("PRUEFEN");
        assertThat(found.get(NaturalDependencyService.DependencyKind.FETCH)).containsExactly("MENU");
        assertThat(result.getExternalCallTargets()).containsExactly("LAGERN01", "MENU");
        assertThat(NdvAnalysis.callKind(NaturalDependencyService.DependencyKind.CALLNAT)).isTrue();
        assertThat(NdvAnalysis.callKind(NaturalDependencyService.DependencyKind.USING)).isFalse();
    }

    @Test
    void theGraphKnowsCallersAndTheHierarchyMarksRecursion() {
        NaturalDependencyGraph graph = new NaturalDependencyGraph();
        graph.addSource("LAGER", "A", "CALLNAT 'B'\nEND");
        graph.addSource("LAGER", "B", "CALLNAT 'C'\nEND");
        graph.addSource("LAGER", "C", "CALLNAT 'A'\nCALLNAT 'X'\nEND");
        graph.build();
        assertThat(graph.getPassiveXRefs("B")).extracting(NaturalDependencyGraph.CallerInfo::getCallerName)
                .containsExactly("A");
        NaturalDependencyGraph.CallHierarchyNode callees = graph.getCallHierarchy("A", true, 5);
        NaturalDependencyGraph.CallHierarchyNode c = callees.getChildren().getFirst().getChildren().getFirst();
        assertThat(c.getObjectName()).isEqualTo("C");
        assertThat(c.getChildren()).extracting(NaturalDependencyGraph.CallHierarchyNode::isRecursive)
                .containsExactly(true, false);
        assertThat(graph.getUnresolvedTargets()).containsExactly("X");
        assertThat(graph.getEntryPoints()).isEmpty();
    }

    @Test
    void accessRulesWorkLikeTheSecurityFilter() {
        NdvAccessRules rules = new NdvAccessRules();
        List<String> events = new ArrayList<>();
        rules.observe(() -> events.add("x"));
        String lib = NdvAccessRules.path("lager", "");
        String obj = NdvAccessRules.path("LAGER", "lager001");
        assertThat(obj).isEqualTo("ndv://LAGER/LAGER001");
        assertThat(rules.isAllowed(obj)).isTrue();
        rules.blacklist(List.of(lib));
        assertThat(rules.isAllowed(obj)).as("Bibliothek gesperrt gilt für alles darin").isFalse();
        assertThat(rules.describe(obj)).isEqualTo("gesperrt");
        rules.whitelist(List.of(lib));
        assertThat(rules.isAllowed(obj)).isTrue();
        rules.blacklist(List.of(obj));
        assertThat(rules.isAllowed(obj)).as("Sperrliste gewinnt").isFalse();
        assertThat(rules.isAllowed(NdvAccessRules.path("LAGER", "LAGER002"))).isTrue();
        assertThat(rules.countBlacklistedDescendants(lib)).isEqualTo(1);
        rules.remove(List.of(obj, lib));
        rules.blacklistAll(true);
        assertThat(rules.isAllowed(obj)).isFalse();
        rules.whitelist(List.of(obj + "/"));
        assertThat(rules.isAllowed(obj)).isTrue();
        assertThat(rules.hasWhitelistedDescendants(lib)).isTrue();
        assertThat(events).hasSize(6);

        NdvAccessRules copy = new NdvAccessRules();
        copy.restore(rules.snapshot());
        assertThat(copy.snapshot()).isEqualTo(rules.snapshot());
    }

    @Test
    void prefetchLoadsOnlyChangedAllowedSourcesAndSurvivesARestart(@TempDir Path folder) throws Exception {
        NdvAccessRules rules = new NdvAccessRules();
        rules.blacklist(List.of(NdvAccessRules.path("LAGER", "LAGERM01")));
        NdvSourceCache cache = new NdvSourceCache("10.0.0.7:8011", new FileNdvSourceStore(folder), rules);
        List<NdvObject> objects = new ArrayList<>();
        session.objects("LAGER", "*", (page, total) -> objects.addAll(page));
        List<String> progress = new ArrayList<>();
        NdvSourceCache.PrefetchListener listener = new NdvSourceCache.PrefetchListener() {
            @Override
            public void progress(int current, int total, String name) {
                progress.add(name);
            }

            @Override
            public void complete(NdvSourceCache.PrefetchResult result) {
            }
        };
        NdvSourceCache.PrefetchResult first = cache.prefetch(session, "LAGER", objects, listener);
        assertThat(first.blocked()).isEqualTo(1);
        assertThat(first.loaded()).isEqualTo(first.total() - 1);
        assertThat(cache.source("LAGER", "LAGER001")).hasValueSatisfying(s -> assertThat(s).contains("READ ARTIKEL"));
        assertThat(cache.source("LAGER", "LAGERM01")).as("gesperrt wird nicht gespeichert").isEmpty();
        assertThat(server.writes()).isEmpty();

        NdvSourceCache.PrefetchResult second = cache.prefetch(session, "LAGER", objects, listener);
        assertThat(second.loaded()).as("unverändert wird nicht neu geladen").isZero();
        assertThat(second.unchanged()).isEqualTo(first.loaded());

        NdvSourceCache restarted = new NdvSourceCache("10.0.0.7:8011", new FileNdvSourceStore(folder), rules);
        assertThat(restarted.size("LAGER")).isEqualTo(first.loaded());
        assertThat(restarted.search("lager", "callnat", 10)).extracting(NdvSourceCache.Hit::name)
                .containsExactly("LAGER002");

        NdvAnalysis analysis = new NdvAnalysis(restarted);
        NaturalDependencyGraph graph = analysis.build("LAGER");
        assertThat(graph.getPassiveXRefs("LAGERN01")).extracting(NaturalDependencyGraph.CallerInfo::getCallerName)
                .containsExactly("LAGER002");
        // „DEFINE DATA LOCAL USING …“ auf einer Zeile (Schein-Server) wird erkannt, anders als in MainframeMate.
        assertThat(graph.getPassiveXRefs("LAGERL01")).extracting(NaturalDependencyGraph.CallerInfo::getCallerName)
                .containsExactly("LAGER001");
        NdvObject lager002 = objects.stream().filter(o -> o.displayName().equals("LAGER002")).findFirst().orElseThrow();
        analysis.saved(lager002, "* geändert\nEND");
        assertThat(analysis.graph("LAGER").orElseThrow().getPassiveXRefs("LAGERN01")).isEmpty();

        analysis.clear("LAGER");
        assertThat(analysis.graph("LAGER")).isEmpty();
        assertThat(new NdvSourceCache("10.0.0.7:8011", new FileNdvSourceStore(folder), rules).size("LAGER")).isZero();
    }

    @Test
    void clearingDuringAReadIsNotUndoneAndLaterBlockedSourcesDisappear(@TempDir Path folder) throws Exception {
        NdvAccessRules rules = new NdvAccessRules();
        NdvSourceCache cache = new NdvSourceCache("10.0.0.7:8011", new FileNdvSourceStore(folder), rules);
        List<NdvObject> objects = new ArrayList<>();
        session.objects("LAGER", "*", (page, total) -> objects.addAll(page));
        // „Cache leeren“, während das erste Objekt gerade gelesen wird (progress kommt direkt vor dem Lesen).
        NdvSourceCache.PrefetchResult result = cache.prefetch(session, "LAGER", objects,
                new NdvSourceCache.PrefetchListener() {
                    @Override
                    public void progress(int current, int total, String name) {
                        cache.invalidate("LAGER");
                    }

                    @Override
                    public void complete(NdvSourceCache.PrefetchResult done) {
                    }
                });
        assertThat(result.cancelled()).isTrue();
        assertThat(cache.size("LAGER")).as("geleert bleibt geleert").isZero();
        assertThat(new NdvSourceCache("10.0.0.7:8011", new FileNdvSourceStore(folder), rules).size("LAGER"))
                .isZero();

        NdvSourceCache.PrefetchListener quiet = new NdvSourceCache.PrefetchListener() {
            @Override
            public void progress(int current, int total, String name) {
            }

            @Override
            public void complete(NdvSourceCache.PrefetchResult done) {
            }
        };
        cache.prefetch(session, "LAGER", objects, quiet);
        assertThat(cache.names("LAGER")).contains("LAGER001");
        rules.blacklist(List.of(NdvAccessRules.path("LAGER", "LAGER001")));
        assertThat(cache.names("LAGER")).as("nachträglich gesperrt").doesNotContain("LAGER001");
        assertThat(cache.sources("LAGER")).doesNotContainKey("LAGER001");
        assertThat(cache.search("LAGER", "READ ARTIKEL", 10)).isEmpty();
        assertThat(cache.source("LAGER", "LAGER001")).isEmpty();
        assertThat(new NdvSourceCache("10.0.0.7:8011", new FileNdvSourceStore(folder), rules).names("LAGER"))
                .as("auch nach einem Neustart").doesNotContain("LAGER001");
    }

    @Test
    void objectNamesWithSpecialCharactersGetTheirOwnFiles(@TempDir Path folder) {
        FileNdvSourceStore store = new FileNdvSourceStore(folder);
        store.put("h:1", new NdvSourceCache.Entry("LIB#1", "A#B", "NSP", "* eins", 6, ""));
        store.put("h:1", new NdvSourceCache.Entry("LIB#1", "A$B", "NSP", "* zwei", 6, ""));
        store.put("h:1", new NdvSourceCache.Entry("LIB$1", "A_B", "NSP", "* drei", 6, ""));
        assertThat(store.load("h:1", "LIB#1")).containsOnlyKeys("A#B.NSP@0-0", "A$B.NSP@0-0");
        assertThat(store.load("h:1", "LIB#1").get("A#B.NSP@0-0").source()).isEqualTo("* eins");
        assertThat(store.load("h:1", "LIB$1")).containsOnlyKeys("A_B.NSP@0-0");
        assertThat(FileNdvSourceStore.safe("..")).isEqualTo("_2E_2E");
        assertThat(FileNdvSourceStore.safe("LAGER001")).isEqualTo("LAGER001");
    }

    @Test
    void sameNameDifferentTypesAndSystemFilesNeverOverwriteAndAmbiguousLookupFails(@TempDir Path folder) {
        var store = new FileNdvSourceStore(folder);
        var program = new NdvSourceCache.Entry("LIB", "BOOK", "NSP", "program", 10, "date", 1, 2);
        var map = new NdvSourceCache.Entry("LIB", "BOOK", "NSM", "map", 10, "date", 1, 2);
        var other = new NdvSourceCache.Entry("LIB", "BOOK", "NSP", "other", 10, "date", 1, 3);
        store.put("account-a", program);
        store.put("account-a", map);
        store.put("account-a", other);
        store.put("account-b", new NdvSourceCache.Entry("LIB", "BOOK", "NSP", "private", 10, "date", 1, 2));
        assertThat(store.load("account-a", "LIB").values()).containsExactlyInAnyOrder(program, map, other);
        var cache = new NdvSourceCache("account-a", store, new NdvAccessRules());
        assertThat(cache.size("LIB")).isEqualTo(3);
        assertThat(cache.entry("LIB", "BOOK")).isEmpty();
        assertThat(cache.sources("LIB")).isEmpty();
        assertThat(cache.search("LIB", "private", 10)).isEmpty();
        assertThat(cache.search("LIB", "map", 10)).extracting(NdvSourceCache.Hit::extension).containsExactly("NSM");
        var exact = new NdvObject("LIB", "BOOK",
                new com.aresstack.spaceharvester.deck.ndv.NdvObjectType(16, "Program", "NSP"),
                true, 10, "", "date", new NdvObject.Location(1, 2, "", 1, false));
        assertThat(cache.entry(exact)).contains(program);
    }

    @Test
    void absentDateOrSizeIsNotProofThatSourceIsUnchanged() {
        var cached = new NdvSourceCache.Entry("LIB", "BOOK", "NSP", "program", 10, "");
        var type = new com.aresstack.spaceharvester.deck.ndv.NdvObjectType(16, "Program", "NSP");
        var location = new NdvObject.Location(1, 2, "", 1, false);
        assertThat(NdvSourceCache.unchanged(cached, new NdvObject("LIB", "BOOK", type,
                true, 10, "", "", location))).isFalse();
        assertThat(NdvSourceCache.unchanged(cached, new NdvObject("LIB", "BOOK", type,
                true, 0, "", "date", location))).isFalse();
    }

    @Test
    void dataAreasOnTheDefineDataLineAreFound() {
        NaturalDependencyService service = new NaturalDependencyService();
        assertThat(usings(service, "DEFINE DATA LOCAL USING LAGERL01\nEND-DEFINE\nEND"))
                .containsExactly("LAGERL01");
        assertThat(usings(service, "DEFINE DATA PARAMETER USING KASSEP01 LOCAL USING KASSEL01\n1 #X (A1)\n"
                + "END-DEFINE\nEND")).containsExactly("KASSEP01", "KASSEL01");
        assertThat(usings(service, "define data local using lagerl02 end-define\nCALLNAT 'LAGERN01'\nEND"))
                .containsExactly("lagerl02");
        assertThat(usings(service, "DEFINE DATA LOCAL\n1 #X (A1)\nEND-DEFINE\nEND")).isEmpty();
    }

    private static List<String> usings(NaturalDependencyService service, String source) {
        return service.analyze(source, "TEST").getGrouped()
                .getOrDefault(NaturalDependencyService.DependencyKind.USING, List.of()).stream()
                .map(NaturalDependencyService.Dependency::getTargetName).toList();
    }

    @Test
    void theResolverFollowsTheSearchOrderAndStepLibs() {
        NdvSourceCache cache = new NdvSourceCache("h:1", new NdvSourceCache.Store() {
            @Override
            public Map<String, NdvSourceCache.Entry> load(String server, String library) {
                return library.equals("KASSE") ? Map.of("KASSE001",
                        new NdvSourceCache.Entry("KASSE", "KASSE001", "NSP", "END", 3, "")) : Map.of();
            }

            @Override
            public void put(String server, NdvSourceCache.Entry entry) {
            }

            @Override
            public void removeLibrary(String server, String library) {
            }
        }, new NdvAccessRules());
        NdvLibraryResolver resolver = new NdvLibraryResolver(List.of("system", "kasse", "SYSTEM"),
                Map.of("abak-m", "abak-t"));
        assertThat(resolver.candidates("", "LAGER")).containsExactly("LAGER", "SYSTEM", "KASSE");
        assertThat(resolver.resolve("kasse001", "", "LAGER", cache)).contains("KASSE");
        assertThat(resolver.resolve("UNBEKANNT", "", "LAGER", cache)).as("sonst die erste").contains("LAGER");
        assertThat(new NdvLibraryResolver(List.of(), Map.of()).resolve("X", "", "", cache)).isEmpty();
        assertThat(resolver.forStepLib("ABAK-M")).contains("ABAK-T");
        assertThat(NdvLibraryResolver.suggest("abak-m")).isEqualTo("ABAK-T");
        assertThat(resolver.withStepLib("x-p", "y").forStepLib("X-P")).contains("Y");
    }
}
