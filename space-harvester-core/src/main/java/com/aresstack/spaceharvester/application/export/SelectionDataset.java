package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Die lokal gespeicherten Seiten einer Wurzel einer Auswahl als Datenbestand, für das Beschaffen von Bildern und
 * Anhängen ohne Export (ADR 0029, Schritt 7): Kopfdaten im Speicher, die Snapshots einzeln aus dem Cache, wenn
 * {@link #forEachPage} sie liefert. Seiten, deren Snapshot inzwischen fehlt, übergeht sie.
 */
public final class SelectionDataset implements ConversionInput {

    private final String sourceId;
    private final String spaceKey;
    private final String rootPageId;
    private final List<PageHeader> headers;
    private final PageTree tree;
    private final Function<PageKey, Optional<PageSnapshot>> snapshots;

    /**
     * @param rootPageId die Seite der Wurzel oder {@code null} für den ganzen Space
     * @param headers    die Kopfdaten der lokal gespeicherten Seiten
     * @param snapshots  der gespeicherte Snapshot einer Seite
     */
    public SelectionDataset(String sourceId, String spaceKey, String rootPageId, List<PageHeader> headers,
                            Function<PageKey, Optional<PageSnapshot>> snapshots) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.spaceKey = Objects.requireNonNull(spaceKey, "spaceKey");
        this.rootPageId = rootPageId;
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        List<TreeEntry> entries = new ArrayList<>(headers.size());
        Map<String, PageHeader> byId = new HashMap<>();
        for (PageHeader header : headers) {
            if (byId.putIfAbsent(header.key().pageId(), header) == null) {
                entries.add(new TreeEntry(header.key().pageId(), header.metadata().parentPageId()));
            }
        }
        this.tree = PageTreeBuilder.build(entries);
        // In Baumreihenfolge: Tiefensuche, Geschwister wie im Baum.
        List<PageHeader> ordered = new ArrayList<>(byId.size());
        Deque<PageTree.Node> open = new ArrayDeque<>();
        List<PageTree.Node> roots = tree.roots();
        for (int i = roots.size() - 1; i >= 0; i--) {
            open.push(roots.get(i));
        }
        while (!open.isEmpty()) {
            PageTree.Node node = open.pop();
            PageHeader header = byId.get(node.pageId());
            if (header != null) {
                ordered.add(header);
            }
            List<PageTree.Node> children = node.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                open.push(children.get(i));
            }
        }
        this.headers = List.copyOf(ordered);
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String spaceKey() {
        return spaceKey;
    }

    @Override
    public String rootPageId() {
        return rootPageId;
    }

    @Override
    public PageTree tree() {
        return tree;
    }

    @Override
    public List<PageHeader> headers() {
        return headers;
    }

    @Override
    public void forEachPage(Consumer<ConvertiblePage> action) {
        for (PageHeader header : headers) {
            snapshots.apply(header.key()).ifPresent(snapshot -> action.accept(new ConvertiblePage(header, snapshot,
                    0)));
        }
    }
}
