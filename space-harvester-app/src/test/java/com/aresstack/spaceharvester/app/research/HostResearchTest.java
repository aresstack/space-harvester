package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.research4j.basket.BasketResearch;
import com.aresstack.research4j.concept.SearchConcept;
import com.aresstack.research4j.judge.FenceFit;
import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.ui.Favorites;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Recherche der Buchfunktion über den Bestand von Space Harvester, mit erfundenen Seiten und einem geskripteten
 * Modell hinter dem {@link AiChat}: Der Korb ist ein Favoritenordner, jede an das Modell gegebene Seite ist vorher
 * freigegeben, Unterseiten werden verfolgt, und eine Seite außerhalb des Weidezauns kommt nicht in den Ordner.
 */
class HostResearchTest {

    @TempDir
    Path dir;

    /** Erfundener Bestand: Quelle „wiki“, Space „GARTEN“. */
    static final class Stock implements PageStock {
        final Map<PageKey, Page> pages = new LinkedHashMap<>();
        final Map<PageKey, List<Child>> children = new LinkedHashMap<>();
        final Set<PageKey> released = ConcurrentHashMap.newKeySet();
        final List<PageKey> sentBeforeRelease = new ArrayList<>();

        Stock() {
            add("1", "Komposthaufen anlegen", "[INNEN] Ein Komposthaufen im Schrebergarten braucht Luft und Feuchte.");
            add("2", "Wurmkiste", "[INNEN] Kompostwürmer zersetzen Küchenabfälle im Schrebergarten.");
            add("3", "Sommerfest", "[AUSSEN] Das Sommerfest im Schrebergarten mit Kuchenbuffet.");
            add("4", "Thermokomposter", "[INNEN] Ein Thermokomposter beschleunigt die Rotte.");
            children.put(new PageKey("wiki", "1"), List.of(new Child(new PageKey("wiki", "4"), "Thermokomposter")));
        }

        private void add(String id, String title, String text) {
            pages.put(new PageKey("wiki", id), new Page(new PageKey("wiki", id), title, text, "GARTEN", null));
        }

        @Override
        public List<KeywordSearch.Hit> search(String text, int limit) {
            List<KeywordSearch.Hit> hits = new ArrayList<>();
            for (Page page : pages.values()) {
                if (page.key().pageId().equals("4")) {
                    continue; // nur über die Unterseiten erreichbar
                }
                String hay = (page.title() + " " + page.text()).toLowerCase(Locale.ROOT);
                for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
                    if (word.length() >= 6 && hay.contains(word)) {
                        hits.add(new KeywordSearch.Hit("wiki", page.key().pageId(), "GARTEN", page.title(), "", null,
                                null, null, null));
                        break;
                    }
                }
            }
            return hits.subList(0, Math.min(limit, hits.size()));
        }

        @Override
        public Optional<Page> page(PageKey key) {
            return Optional.ofNullable(pages.get(key));
        }

        @Override
        public List<Child> children(PageKey key, String spaceKey) {
            return children.getOrDefault(key, List.of());
        }

        final List<Map<PageKey, String>> batches = new ArrayList<>();

        @Override
        public synchronized void release(Map<PageKey, String> spaceOfPage) {
            batches.add(spaceOfPage);
            released.addAll(spaceOfPage.keySet());
        }
    }

    /** Planer: zwei Varianten je Knoten; Prüfer: die Marke im Anfang der Seite. Merkt sich, welche Seiten es sah. */
    static final class Model implements AiChat {
        private static final Pattern ADDRESS = Pattern.compile("Adresse: (\\S+)");
        private static final Pattern MARK = Pattern.compile("\\[(INNEN|GEMISCHT|AUSSEN|UNKLAR)]");
        private static final Pattern NODE = Pattern.compile("NODE\\n(.+)\\n");
        final Set<String> seenPages = ConcurrentHashMap.newKeySet();
        /** Seiten, deren Text das Modell sah, bevor sie freigegeben waren; muss leer bleiben. */
        final Set<String> unreleased = ConcurrentHashMap.newKeySet();
        Set<PageKey> released = Set.of();

        @Override
        public Task stream(Request request, Listener listener) {
            String system = request.messages().get(0).text();
            String prompt = request.messages().get(request.messages().size() - 1).text();
            String answer;
            if (system.contains("search planner")) {
                Matcher node = NODE.matcher(prompt);
                String label = node.find() ? node.group(1) : "Kompost";
                answer = "{\"queries\":[\"" + label + " Anleitung\",\"" + label + " Tipps\"]}";
            } else {
                Matcher address = ADDRESS.matcher(prompt);
                String page = address.find() ? address.group(1) : "";
                seenPages.add(page);
                if (!page.isEmpty() && !released.contains(new PageKey("wiki", page.substring(page.indexOf('/') + 1)))) {
                    unreleased.add(page);
                }
                Matcher mark = MARK.matcher(prompt);
                answer = "{\"subject\":\"" + page + "\",\"fence\":\"" + (mark.find() ? mark.group(1) : "UNKLAR")
                        + "\",\"reason\":\"geskriptet\"}";
            }
            listener.completed(answer);
            return () -> {
            };
        }
    }

    private static final Fence FENCE = new Fence("Kompostieren im Schrebergarten", "", List.of("Wurmkiste"),
            List.of("Vereinsfeste"), "", "");

    @Test
    void dieRechercheFuelltDenFavoritenordnerUndGibtJedeGeleseneSeiteVorherFrei() {
        Stock stock = new Stock();
        Model model = new Model();
        model.released = stock.released;
        Favorites favorites = new Favorites(() -> dir.resolve("favorites.json"));
        String folder = favorites.createFolder("Korb Kompost", null);
        FavoritesBasket basket = new FavoritesBasket(favorites, folder, id -> "Wiki");

        BasketResearch.Result result = HostResearch.research(new AiChatModel(model, "test-modell", null, 0), stock,
                basket, 10, BasketResearch.Settings.defaults("de", 1, 1))
                .run(FENCE, SearchConcept.ofMission(FENCE.getMission()), basket.basket(), null, null);

        assertThat(favorites.favoritesIn(folder, true)).extracting(Favorites.Favorite::pageId)
                .containsExactlyInAnyOrder("1", "2", "4");
        assertThat(basket.basket().sources()).hasSize(3);
        assertThat(result.profiles()).anyMatch(p -> p.source().id().equals("wiki/3") && p.fit() == FenceFit.AUSSEN);
        assertThat(model.seenPages).isNotEmpty();
        assertThat(model.unreleased).as("jede Seite vor dem Senden freigegeben").isEmpty();
        assertThat(stock.batches).as("gebündelt: höchstens eine Freigabe je Runde, nicht eine je Seite")
                .hasSizeLessThanOrEqualTo(result.rounds()).hasSizeLessThan(stock.released.size());
        assertThat(favorites.favoritesIn(folder, true)).allSatisfy(f -> assertThat(f.spaceKey()).isEqualTo("GARTEN"));
    }

    @Test
    void eineSeiteImKorbWirdNichtNochEinmalGelesen() {
        Stock stock = new Stock();
        Model model = new Model();
        Favorites favorites = new Favorites(() -> dir.resolve("favorites.json"));
        String folder = favorites.createFolder("Korb", null);
        favorites.toggle(Favorites.Favorite.page("wiki", "GARTEN", "2", "Wurmkiste", null, "Wiki"), folder);
        FavoritesBasket basket = new FavoritesBasket(favorites, folder, id -> "Wiki");

        HostResearch.research(new AiChatModel(model, "test-modell", null, 0), stock, basket, 10,
                BasketResearch.Settings.defaults("de", 1, 1)).run(FENCE, null, basket.basket(), null, null);

        assertThat(model.seenPages).doesNotContain("wiki/2");
        assertThat(favorites.favoritesIn(folder, true)).extracting(Favorites.Favorite::pageId).contains("1", "2");
    }

    @Test
    void eineSeiteOhneSpaceGehtNichtAnDasModell() {
        Stock stock = new Stock();
        stock.pages.put(new PageKey("wiki", "1"), new PageStock.Page(new PageKey("wiki", "1"), "Komposthaufen anlegen",
                "[INNEN] Schrebergarten Kompost", null, null));
        Model model = new Model();
        Favorites favorites = new Favorites(() -> dir.resolve("favorites.json"));
        String folder = favorites.createFolder("Korb", null);
        FavoritesBasket basket = new FavoritesBasket(favorites, folder, null);

        BasketResearch.Result result = HostResearch.research(new AiChatModel(model, "m", null, 0), stock, basket, 10,
                BasketResearch.Settings.defaults("de", 1, 1)).run(FENCE, null, null, null, null);

        assertThat(model.seenPages).doesNotContain("wiki/1");
        assertThat(result.skipped()).anyMatch(s -> s.what().equals("wiki/1"));
    }
}
