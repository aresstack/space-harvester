package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Die Favoritenansicht des Explorers (ADR 0034, Paket 2a): heute die flache Liste mit Pfad, die der Stern-Umschalter
 * unten rechts an Stelle des Baums zeigt. Aus dem Explorer gelöst, damit der Ordnerbaum (Paket 2b) sie ersetzen kann,
 * ohne den Explorer anzufassen. Verhalten unverändert: ein Klick oder Enter öffnet den Favoriten, die Liste markiert
 * den Favoriten des im Baum gewählten Knotens.
 */
final class FavoritesView {

    private final AppContext context;
    /** Der im Baum gewählte Knoten, damit die Liste dessen Favoriten markiert; null ohne Auswahl. */
    private final Supplier<Explorer.Item> current;
    /** Zeigt eine ganze Quelle im Baum; Mitte und rechts folgen der Auswahl. */
    private final Consumer<String> revealSource;
    private final DefaultListModel<Favorites.Favorite> model = new DefaultListModel<>();
    private final JList<Favorites.Favorite> list = new JList<>(model);
    private final JScrollPane component;

    FavoritesView(AppContext context, Supplier<Explorer.Item> current, Consumer<String> revealSource) {
        this.context = Objects.requireNonNull(context, "context");
        this.current = Objects.requireNonNull(current, "current");
        this.revealSource = Objects.requireNonNull(revealSource, "revealSource");
        list.setFont(Comic.regular(13f));
        list.setOpaque(false);
        list.setSelectionBackground(Comic.YELLOW);
        list.setSelectionForeground(Comic.INK);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> owner, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(owner, value, index, selected, focus);
                if (value instanceof Favorites.Favorite favorite) {
                    // Der Favorit selbst in Tinte, seine Eltern als Pfad gedämpft darunter.
                    setText("<html>" + Explorer.escape(favorite.title()) + "<br><font color='#7a7a7a'>"
                            + Explorer.escape(favorite.where()) + "</font></html>");
                    setIcon(favorite.isSource() ? MainWindow.database()
                            : favorite.pageId() == null ? SidebarIcons.folder() : SidebarIcons.page());
                }
                setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
                setOpaque(selected);
                return this;
            }
        });
        list.getAccessibleContext().setAccessibleName("Favoriten");
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    open();
                }
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "favorite-open");
        list.getActionMap().put("favorite-open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        });
        component = Comic.scroll(list);
        component.setBorder(BorderFactory.createEmptyBorder());
    }

    JComponent component() {
        return component;
    }

    /** Liest die Favoriten neu ein und markiert den aktuellen; liefert, ob es keine Favoriten gibt. */
    boolean reload() {
        List<Favorites.Favorite> all = context.favorites().all();
        model.clear();
        all.forEach(model::addElement);
        markCurrent();
        return all.isEmpty();
    }

    boolean isEmpty() {
        return model.isEmpty();
    }

    /**
     * Markiert den Favoriten des im Baum gewählten Knotens, also der geöffneten Seite. Ohne diese Markierung sah ein
     * Klick auf den Favoriten der schon offenen Seite so aus, als passiere nichts.
     */
    void markCurrent() {
        Explorer.Item item = current.get();
        for (int index = 0; item != null && index < model.size(); index++) {
            if (matches(model.get(index), item)) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
                return;
            }
        }
        list.clearSelection();
    }

    static boolean matches(Favorites.Favorite favorite, Explorer.Item item) {
        return switch (item) {
            case Explorer.SourceItem source -> favorite.isSource() && favorite.sourceId().equals(source.sourceId());
            case Explorer.SpaceItem space -> !favorite.isSource() && favorite.pageId() == null
                    && favorite.sourceId().equals(space.sourceId())
                    && Objects.equals(favorite.spaceKey(), space.spaceKey());
            case Explorer.PageItem page -> favorite.sourceId().equals(page.sourceId())
                    && page.pageId().equals(favorite.pageId());
            default -> false;
        };
    }

    /** Ein Favorit: die Seite öffnen bzw. den Space zeigen; der Baum folgt der geöffneten Seite. */
    private void open() {
        Favorites.Favorite favorite = list.getSelectedValue();
        if (favorite == null) {
            return;
        }
        if (favorite.isSource()) {
            revealSource.accept(favorite.sourceId());
        } else if (favorite.pageId() == null) {
            context.navigator().openSpace(favorite.sourceId(), favorite.spaceKey());
        } else {
            context.navigator().openPage(favorite.sourceId(), favorite.pageId());
        }
    }

    /** Für Tests: die Liste selbst. */
    JList<Favorites.Favorite> list() {
        return list;
    }
}
