package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.holdings.PageHolding;
import com.aresstack.spaceharvester.application.holdings.SpaceHoldings;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.observe.Subscription;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Die Bestandszeichen des Explorers (Angelo, 13.11.2026, Vorbild OneDrive): je Seite Wolke (nur in der Quelle), Haken
 * (lokal und aktuell), Kreispfeile (wird geladen), Warndreieck (veraltet) oder Schloss (gesperrt); Knoten mit Kindern
 * und Spaces voll, halb oder leer, Kreispfeile solange im Teilbaum geladen wird. Der Tooltip nennt die Zahlen.
 *
 * <p>Alles kommt aus der Bestandsübersicht des Core ({@code Workspace.holdings}, ADR 0027) und ändert sich nur durch
 * ihre Meldungen ({@code subscribeHoldings}), nie durch Zeitgeber: Je Quelle eine Anmeldung für alle Spaces; die
 * Übersicht eines Space wird beim Aufklappen einmal gelesen und danach nur für die gemeldeten Seiten samt Vorfahren
 * nachgelesen. Meldungen kommen auf dem Zustell-Thread des Core; gelesen wird im Hintergrund, gezeichnet auf dem EDT.
 */
final class TreeStates {

    /** Was das Zeichen sagt. */
    enum Kind { NONE, REMOTE, LOCAL, LOADING, OUTDATED, LOCKED, FULL, PARTIAL, EMPTY }

    /** Ein Zeichen samt Farbe und Tooltip; {@link Kind#NONE} ohne Zeichen. */
    record Badge(Kind kind, Glyph glyph, Color color, String tooltip) {
        static final Badge NONE = new Badge(Kind.NONE, null, null, null);
    }

    static final String TIP_LOCAL = "Lokal und aktuell";
    static final String TIP_REMOTE = "Nur in der Quelle, kein Inhalt lokal";
    static final String TIP_LOADING = "Wird geladen";
    static final String TIP_OUTDATED = "Lokal, aber veraltet";
    static final String TIP_LOCKED = "Gesperrt: für das Konto nicht sichtbar";
    static final String TIP_UNKNOWN = "Noch keine Inventur";

    private final AppContext context;
    /** Läuft auf dem EDT, wenn sich ein Zeichen geändert haben kann (der Baum zeichnet neu). */
    private final Runnable changed;
    /** Übersicht je aufgeklapptem Space, Schlüssel {@link #key}. */
    private final Map<String, SpaceHoldings> spaces = new HashMap<>();
    /** Stand je Space einer Quelle ohne Baum, Schlüssel {@link #key}. */
    private final Map<String, SpaceSummary> summaries = new HashMap<>();
    /** Seiten, deren Laden gemeldet und noch nicht beendet ist, je Space. */
    private final Map<String, Set<String>> loading = new HashMap<>();
    /** Vorfahren ladender Seiten je Space; neu berechnet, wenn sich Übersicht oder Ladeliste ändern. */
    private final Map<String, Set<String>> loadingAbove = new HashMap<>();
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    /** Spaces, deren Übersicht gerade gelesen wird; eine Meldung währenddessen merkt den Space als veraltet. */
    private final Set<String> reading = new HashSet<>();
    private final Set<String> stale = new HashSet<>();
    /** Quellen, deren Stand je Space gerade gelesen wird; Meldungen währenddessen führen zu genau einem Nachlesen. */
    private final Set<String> readingSources = new HashSet<>();
    private final Set<String> staleSources = new HashSet<>();
    private AppRuntime subscribed;

    TreeStates(AppContext context, Runnable changed) {
        this.context = context;
        this.changed = changed;
    }

    static String key(String sourceId, String spaceKey) {
        return sourceId + "\u0000" + spaceKey;
    }

    // ------------------------------------------------------------------ Anmelden und Lesen

    /** Die Quellen des Baums: je Quelle eine Anmeldung für alle Spaces und einmal der Stand je Space. EDT. */
    void watch(List<String> sourceIds) {
        AppRuntime runtime = context.runtime();
        List<String> open = List.of();
        if (runtime != subscribed) {
            // Neue Laufzeit, womöglich mit denselben Quellen: alles neu anmelden, aufgeklappte Spaces neu lesen.
            open = new ArrayList<>(spaces.keySet());
            close();
            subscribed = runtime;
        }
        if (runtime == null) {
            return;
        }
        for (String sourceId : sourceIds) {
            if (subscriptions.containsKey(sourceId)) {
                continue;
            }
            subscriptions.put(sourceId, runtime.workspace().subscribeHoldings(sourceId, "", new HoldingsListener() {
                @Override
                public void pagesChanged(String source, String spaceKey, Set<String> pageIds) {
                    SwingUtilities.invokeLater(() -> TreeStates.this.pagesChanged(source, spaceKey, pageIds));
                }

                @Override
                public void spaceChanged(String source, String spaceKey) {
                    SwingUtilities.invokeLater(() -> TreeStates.this.spaceChanged(source, spaceKey));
                }

                @Override
                public void loading(String source, String spaceKey, Set<String> pageIds) {
                    SwingUtilities.invokeLater(() -> TreeStates.this.loadingStarted(source, spaceKey, pageIds));
                }
            }));
            readSummaries(runtime, sourceId);
        }
        // Erst anmelden, dann lesen: die aufgeklappten Spaces der alten Laufzeit erst nach den Anmeldungen.
        for (String key : open) {
            int cut = key.indexOf('\u0000');
            readSpace(runtime, key.substring(0, cut), key.substring(cut + 1));
        }
    }

    /** Ein Space wird aufgeklappt: seine Übersicht einmal lesen. EDT. */
    void open(String sourceId, String spaceKey) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || runtime != subscribed || spaces.containsKey(key(sourceId, spaceKey))) {
            return;
        }
        readSpace(runtime, sourceId, spaceKey);
    }

    void close() {
        for (Subscription subscription : subscriptions.values()) {
            subscription.close();
        }
        subscriptions.clear();
        spaces.clear();
        reading.clear();
        stale.clear();
        readingSources.clear();
        staleSources.clear();
        summaries.clear();
        loading.clear();
        loadingAbove.clear();
    }

    private void readSummaries(AppRuntime runtime, String sourceId) {
        if (!readingSources.add(sourceId)) {
            // Der Stand aller Spaces der Quelle wird gerade gerechnet: danach genau einmal nachlesen.
            staleSources.add(sourceId);
            return;
        }
        Background.run(() -> runtime.workspace().holdingSpaces(sourceId), list -> {
            readingSources.remove(sourceId);
            if (runtime != subscribed) {
                return;
            }
            // Der Stand der Quelle wird ersetzt, nicht ergänzt: nach „Daten bereinigen“ kennt der Cache Spaces
            // nicht mehr, deren Zeichen sonst stehen blieben. Aufgeklappte Spaces behalten ihren eigenen Stand.
            Set<String> known = new HashSet<>();
            for (SpaceSummary summary : list) {
                known.add(key(sourceId, summary.spaceKey()));
            }
            summaries.keySet().removeIf(key -> key.startsWith(sourceId + "\u0000") && !known.contains(key)
                    && !spaces.containsKey(key));
            for (SpaceSummary summary : list) {
                summaries.put(key(sourceId, summary.spaceKey()), summary);
            }
            changed.run();
            if (staleSources.remove(sourceId)) {
                readSummaries(runtime, sourceId);
            }
        }, error -> {
            // Ohne Zugriff auf die Metadaten der Quelle bleibt der Baum ohne Zeichen.
            readingSources.remove(sourceId);
            staleSources.remove(sourceId);
        });
    }

    private void readSpace(AppRuntime runtime, String sourceId, String spaceKey) {
        String key = key(sourceId, spaceKey);
        if (!reading.add(key)) {
            // Läuft schon: das Ergebnis könnte älter sein als der Anlass, also danach noch einmal lesen.
            stale.add(key);
            return;
        }
        Background.run(() -> runtime.workspace().holdings(sourceId, spaceKey), holdings -> {
            reading.remove(key);
            if (runtime != subscribed) {
                return;
            }
            spaces.put(key, holdings);
            summaries.put(key, holdings.space());
            loadingAbove.remove(key);
            changed.run();
            if (stale.remove(key)) {
                readSpace(runtime, sourceId, spaceKey);
            }
        }, error -> {
            // Zu groß für einen Baum oder kein Zugriff: die Seiten dieses Space bleiben ohne Zeichen.
            reading.remove(key);
            stale.remove(key);
        });
    }

    private void pagesChanged(String sourceId, String spaceKey, Set<String> pageIds) {
        AppRuntime runtime = context.runtime();
        String key = key(sourceId, spaceKey);
        Set<String> running = loading.get(key);
        if (running != null) {
            running.removeAll(pageIds);
            loadingAbove.remove(key);
        }
        SpaceHoldings known = spaces.get(key);
        if (runtime == null || runtime != subscribed) {
            return;
        }
        if (reading.contains(key)) {
            // Die erste Übersicht ist unterwegs und könnte die Meldung noch nicht enthalten: danach noch einmal lesen.
            stale.add(key);
            return;
        }
        if (known == null) {
            // Nicht aufgeklappt: nur der Stand des Space ändert sich, ohne Baum, denn der kann zu groß sein.
            readSummaries(runtime, sourceId);
            return;
        }
        Background.run(() -> runtime.workspace().holdings(sourceId, spaceKey, pageIds), part -> {
            if (runtime != subscribed) {
                return;
            }
            SpaceHoldings current = spaces.get(key);
            if (current == null) {
                return;
            }
            Map<String, PageHolding> pages = new HashMap<>(current.pages());
            for (PageHolding page : part.pages().values()) {
                if (page.state() == HoldingState.REMOVED) {
                    pages.remove(page.pageId());
                } else {
                    pages.put(page.pageId(), page);
                }
            }
            spaces.put(key, new SpaceHoldings(part.space(), pages));
            summaries.put(key, part.space());
            loadingAbove.remove(key);
            changed.run();
        }, error -> {
        });
    }

    private void spaceChanged(String sourceId, String spaceKey) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || runtime != subscribed) {
            return;
        }
        if (spaceKey == null || spaceKey.isEmpty()) {
            // Alle Spaces der Quelle: Übersichten vergessen, Stand neu lesen; aufgeklappte lesen beim nächsten Zeichnen.
            List<String> open = new ArrayList<>();
            spaces.keySet().removeIf(key -> {
                boolean mine = key.startsWith(sourceId + "\u0000");
                if (mine) {
                    open.add(key.substring(sourceId.length() + 1));
                }
                return mine;
            });
            loading.keySet().removeIf(key -> key.startsWith(sourceId + "\u0000"));
            loadingAbove.keySet().removeIf(key -> key.startsWith(sourceId + "\u0000"));
            readSummaries(runtime, sourceId);
            for (String space : open) {
                readSpace(runtime, sourceId, space);
            }
            return;
        }
        String key = key(sourceId, spaceKey);
        loading.remove(key);
        loadingAbove.remove(key);
        if (spaces.containsKey(key)) {
            readSpace(runtime, sourceId, spaceKey);
        } else {
            readSummaries(runtime, sourceId);
        }
    }

    private void loadingStarted(String sourceId, String spaceKey, Set<String> pageIds) {
        String key = key(sourceId, spaceKey);
        loading.computeIfAbsent(key, ignored -> new HashSet<>()).addAll(pageIds);
        loadingAbove.remove(key);
        changed.run();
    }

    // ------------------------------------------------------------------ Zeichen

    /** Das Zeichen eines Space: voll, halb, leer nach dem Stand; Kreispfeile, solange darin geladen wird. */
    Badge space(String sourceId, String spaceKey) {
        String key = key(sourceId, spaceKey);
        SpaceSummary summary = summaries.get(key);
        if (summary == null) {
            return Badge.NONE;
        }
        Set<String> running = loading.get(key);
        return node(summary.inventoried() ? summary.counts() : null, running != null && !running.isEmpty());
    }

    /** Das Zeichen einer Seite; mit Kindern nach ihrem Teilbaum, sonst nach ihrem eigenen Zustand. */
    Badge page(String sourceId, String spaceKey, String pageId, boolean hasChildren) {
        String key = key(sourceId, spaceKey);
        SpaceHoldings holdings = spaces.get(key);
        if (holdings == null) {
            return Badge.NONE;
        }
        PageHolding page = holdings.pages().get(pageId);
        Set<String> running = loading.getOrDefault(key, Set.of());
        boolean self = running.contains(pageId);
        if (page == null) {
            return self ? leaf(HoldingState.REMOTE, true) : Badge.NONE;
        }
        if (!hasChildren || page.subtree().pages() <= 1) {
            return leaf(page.state(), self);
        }
        return node(page.subtree(), self || loadingAbove(key, holdings, running).contains(pageId));
    }

    /** Vorfahren aller ladenden Seiten eines Space, einmal je Stand berechnet. */
    private Set<String> loadingAbove(String key, SpaceHoldings holdings, Set<String> running) {
        Set<String> above = loadingAbove.get(key);
        if (above == null) {
            above = ancestors(holdings.pages(), running);
            loadingAbove.put(key, above);
        }
        return above;
    }

    static Set<String> ancestors(Map<String, PageHolding> pages, Set<String> pageIds) {
        Set<String> above = new HashSet<>();
        for (String pageId : pageIds) {
            PageHolding page = pages.get(pageId);
            int guard = 0;
            while (page != null && page.parentPageId() != null && guard++ < 10_000) {
                if (!above.add(page.parentPageId())) {
                    break;
                }
                page = pages.get(page.parentPageId());
            }
        }
        return above;
    }

    /** Das Zeichen einer Seite ohne Kinder (oder mit nur sich selbst im Teilbaum). */
    static Badge leaf(HoldingState state, boolean loading) {
        if (loading) {
            return new Badge(Kind.LOADING, Glyph.SYNC, Comic.BLUE, TIP_LOADING);
        }
        return switch (state) {
            case LOCAL -> new Badge(Kind.LOCAL, Glyph.CHECK_CIRCLE, Comic.GREEN, TIP_LOCAL);
            case OUTDATED -> new Badge(Kind.OUTDATED, Glyph.WARNING, Comic.ORANGE, TIP_OUTDATED);
            case LOCKED -> new Badge(Kind.LOCKED, Glyph.LOCK, Comic.RED, TIP_LOCKED);
            case REMOTE, REMOVED -> new Badge(Kind.REMOTE, Glyph.CLOUD, Comic.MUTED, TIP_REMOTE);
        };
    }

    /** Das Zeichen eines Knotens mit Kindern oder eines Space; {@code counts == null} ohne Inventur. */
    static Badge node(HoldingCounts counts, boolean loading) {
        if (counts == null) {
            return new Badge(loading ? Kind.LOADING : Kind.EMPTY, loading ? Glyph.SYNC : Glyph.CIRCLE_EMPTY,
                    loading ? Comic.BLUE : Comic.MUTED, loading ? TIP_LOADING : TIP_UNKNOWN);
        }
        String tip = tooltip(counts);
        if (loading) {
            return new Badge(Kind.LOADING, Glyph.SYNC, Comic.BLUE, tip + " · " + TIP_LOADING.toLowerCase(java.util.Locale.ROOT));
        }
        int stored = counts.local() + counts.outdated();
        if (counts.pages() == 0 || stored == 0) {
            return new Badge(Kind.EMPTY, Glyph.CIRCLE_EMPTY, Comic.MUTED, tip);
        }
        if (stored >= counts.pages() - counts.locked()) {
            return new Badge(counts.outdated() > 0 ? Kind.PARTIAL : Kind.FULL,
                    counts.outdated() > 0 ? Glyph.CIRCLE_HALF : Glyph.CIRCLE_FULL,
                    counts.outdated() > 0 ? Comic.ORANGE : Comic.GREEN, tip);
        }
        return new Badge(Kind.PARTIAL, Glyph.CIRCLE_HALF, Comic.INK, tip);
    }

    /** „184 von 2010 Seiten lokal, 8 veraltet, 2 gesperrt“. */
    static String tooltip(HoldingCounts counts) {
        StringBuilder tip = new StringBuilder();
        tip.append(counts.local() + counts.outdated()).append(" von ").append(counts.pages())
                .append(counts.pages() == 1 ? " Seite" : " Seiten").append(" lokal");
        if (counts.outdated() > 0) {
            tip.append(", ").append(counts.outdated()).append(" veraltet");
        }
        if (counts.locked() > 0) {
            tip.append(", ").append(counts.locked()).append(" gesperrt");
        }
        return tip.toString();
    }

    // ------------------------------------------------------------------ für Tests

    boolean knows(String sourceId, String spaceKey) {
        return spaces.containsKey(key(sourceId, spaceKey));
    }
}
