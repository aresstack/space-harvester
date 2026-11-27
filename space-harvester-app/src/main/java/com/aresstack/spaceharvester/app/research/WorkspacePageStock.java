package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.app.chat.ChatTools;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Der Bestand über den {@link Workspace}: dieselbe Schlagwortsuche wie die Suchleiste ({@code keywordSearchIn}, wie der
 * Chat sie fragt), dieselbe Lesart wie das Chat-Werkzeug {@code seite_lesen} ({@link ChatTools#fetchedPage}), die
 * Unterseiten live aus der Navigation und die KI-Freigabe wie im Chat ({@code releasePageForChat}).
 */
public final class WorkspacePageStock implements PageStock {

    private final Workspace workspace;
    private final Function<String, KeywordSearch.Query> scope;

    /**
     * @param scope macht aus einem Suchtext die Anfrage im Umfang der Recherche; {@link KeywordSearch.Query#of} für
     *              alle freigegebenen Quellen
     */
    public WorkspacePageStock(Workspace workspace, Function<String, KeywordSearch.Query> scope) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.scope = scope == null ? KeywordSearch.Query::of : scope;
    }

    @Override
    public List<KeywordSearch.Hit> search(String text, int limit) {
        String query = text.strip();
        if (query.length() > KeywordSearch.MAX_TEXT_LENGTH) {
            query = query.substring(0, KeywordSearch.MAX_TEXT_LENGTH).strip();
        }
        return workspace.keywordSearchIn(UUID.randomUUID().toString(), scope.apply(query), null, limit, null)
                .ranked();
    }

    @Override
    public Optional<Page> page(PageKey key) {
        ChatTools.Page page = ChatTools.fetchedPage(workspace, key);
        return page == null ? Optional.empty()
                : Optional.of(new Page(key, page.title(), page.text(), page.spaceKey(), page.location()));
    }

    @Override
    public List<Child> children(PageKey key, String spaceKey) {
        List<Child> children = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        try {
            do {
                PageNavigation.Children batch = workspace.children(key.sourceId(), spaceKey, key.pageId(), cursor);
                batch.pages().forEach(node -> children.add(new Child(new PageKey(key.sourceId(), node.pageId()),
                        node.title())));
                cursor = batch.nextCursor();
            } while (cursor != null && cursors.add(cursor));
        } catch (RuntimeException noTree) {
            // Quellen ohne Seitenbaum (UNSUPPORTED) oder eine abgelehnte Navigation: dann ohne Unterseiten.
            return children;
        }
        return children;
    }

    @Override
    public void release(String spaceKey, PageKey key) {
        workspace.releasePageForChat(spaceKey, key);
    }
}
