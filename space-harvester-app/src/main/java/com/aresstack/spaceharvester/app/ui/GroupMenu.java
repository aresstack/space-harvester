package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.group.GroupColor;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.controls.ColorDot;
import com.aresstack.spaceharvester.app.ui.controls.ColorSwatches;
import com.aresstack.spaceharvester.app.ui.controls.GroupColors;

import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Farbige Gruppen als erweiterte Favoriten (Angelo 27.11.2026): Eine Gruppe ist ein Favoritenordner mit Farbe
 * ({@link Favorites.Folder#color()}). Hier steht die EINE Auswahl „Gruppe zuordnen“ für alle Stellen: als Untermenü im
 * Rechtsklick-Menü eines Knotens oder Treffers und, nur mit den Gruppen und „Neue Gruppe …“, als Rechtsklick auf den
 * Stern. Zugeordnet wird der Knoten Favorit in diesem Ordner; ein Favorit liegt immer nur in einer Gruppe.
 */
public final class GroupMenu {

    static final String ASSIGN = "Gruppe zuordnen";
    static final String NEW_GROUP = "Neue Gruppe …";
    static final String NEW_GROUP_TITLE = "Neue Gruppe";
    static final String COLOR = "Farbe";
    static final String NO_COLOR = "Keine Farbe (gewöhnlicher Ordner)";
    public static final String STAR_TIP = "Klick: Favorit · Rechtsklick: Gruppe zuordnen";

    private GroupMenu() {
    }

    /** Rechtsklick auf den Stern: nur die vorhandenen Gruppen und „Neue Gruppe …“. */
    public static JPopupMenu popup(Component owner, Favorites favorites, String currentGroupId, Consumer<String> assign) {
        JPopupMenu menu = new JPopupMenu();
        fill(menu, owner, favorites, currentGroupId, assign);
        return menu;
    }

    /** „Gruppe zuordnen“ als Untermenü eines Rechtsklick-Menüs. */
    static JMenu submenu(Component owner, Favorites favorites, String currentGroupId, Consumer<String> assign) {
        JMenu menu = new JMenu(ASSIGN);
        fill(menu, owner, favorites, currentGroupId, assign);
        return menu;
    }

    private static void fill(Container menu, Component owner, Favorites favorites, String currentGroupId,
                             Consumer<String> assign) {
        List<Favorites.Folder> groups = favorites.groups();
        for (Favorites.Folder group : groups) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(group.name(),
                    new ColorDot(GroupColors.of(group.color())), group.id().equals(currentGroupId));
            item.setToolTipText(group.color().label());
            item.addActionListener(e -> assign.accept(group.id()));
            menu.add(item);
        }
        if (!groups.isEmpty()) {
            menu.add(new JPopupMenu.Separator());
        }
        JMenuItem create = new JMenuItem(NEW_GROUP);
        create.addActionListener(e -> {
            String id = createGroup(owner, favorites);
            if (id != null) {
                assign.accept(id);
            }
        });
        menu.add(create);
    }

    /**
     * Fragt Name und Farbe einer neuen Gruppe und legt sie als farbigen Favoritenordner oben an. Vorgewählt ist die
     * erste Farbe, die noch keine Gruppe trägt; ohne Namen heißt die Gruppe wie ihre Farbe.
     *
     * @return die ID der neuen Gruppe oder {@code null}, wenn abgebrochen wurde
     */
    static String createGroup(Component owner, Favorites favorites) {
        GroupColor proposed = GroupColor.next(favorites.groups().stream().map(Favorites.Folder::color).toList());
        JTextField name = new JTextField(24);
        Comic.style(name);
        ColorSwatches swatches = new ColorSwatches(GroupColors.of(proposed),
                color -> GroupColor.valueOf(color.name()).label());
        JPanel body = new JPanel(new GridLayout(0, 1, 0, 6));
        body.setOpaque(false);
        body.add(new JLabel("Name"));
        body.add(name);
        body.add(new JLabel("Farbe"));
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(swatches, BorderLayout.WEST);
        body.add(row);
        javax.swing.SwingUtilities.invokeLater(name::requestFocusInWindow);
        if (!Dialogs.formOverlay(owner, NEW_GROUP_TITLE, body, "Anlegen")) {
            return null;
        }
        GroupColor color = GroupColor.valueOf(swatches.selected().name());
        String typed = name.getText().strip();
        return favorites.createFolder(typed.isEmpty() ? color.label() : typed, null, color);
    }

    /**
     * Der Stern eines Knotens, als Favorit gefüllt; liegt er in einer Gruppe, in deren Farbe (die Farbe ist Marke am
     * Stern, nie am Text).
     */
    public static Icon star(boolean favorite, GroupColor color) {
        Glyph glyph = favorite ? Glyph.STAR_FILLED : Glyph.STAR;
        if (color == null) {
            return glyph;
        }
        return new Icon() {
            private final JLabel host = new JLabel();

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Color background = c == null ? null : c.getBackground();
                host.setForeground(GroupColors.of(color).on(background));
                glyph.paintIcon(host, g, x, y);
            }

            @Override
            public int getIconWidth() {
                return glyph.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return glyph.getIconHeight();
            }
        };
    }

    /** Das Symbol eines Ordners; als Gruppe mit dem Farbpunkt dahinter. */
    static Icon folderIcon(Icon base, GroupColor color) {
        if (color == null) {
            return base;
        }
        ColorDot dot = new ColorDot(GroupColors.of(color));
        int gap = 4;
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                base.paintIcon(c, g, x, y + (getIconHeight() - base.getIconHeight()) / 2);
                dot.paintIcon(c, g, x + base.getIconWidth() + gap, y + (getIconHeight() - dot.getIconHeight()) / 2);
            }

            @Override
            public int getIconWidth() {
                return base.getIconWidth() + gap + dot.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return Math.max(base.getIconHeight(), dot.getIconHeight());
            }
        };
    }

    /**
     * „Farbe“ am Ordner der Favoritenansicht: macht ihn zur Gruppe, färbt sie um oder nimmt die Farbe weg (dann ist er
     * wieder ein gewöhnlicher Ordner, seine Favorites bleiben).
     */
    static JMenu colorMenu(Favorites favorites, String folderId) {
        GroupColor current = favorites.folder(folderId).map(Favorites.Folder::color).orElse(null);
        JMenu menu = new JMenu(COLOR);
        for (GroupColor color : GroupColor.values()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(color.label(), new ColorDot(GroupColors.of(color)),
                    color == current);
            item.addActionListener(e -> favorites.recolorFolder(folderId, color));
            menu.add(item);
        }
        menu.add(new JPopupMenu.Separator());
        JCheckBoxMenuItem none = new JCheckBoxMenuItem(NO_COLOR, current == null);
        none.addActionListener(e -> favorites.recolorFolder(folderId, null));
        menu.add(none);
        return menu;
    }

    /**
     * Der Favorit einer Seite für Stern und Gruppe, wie der Stern im Baum ihn baut: ist sie schon Favorit, genügt der
     * Knoten (der gemerkte Pfad bleibt); sonst merkt sie sich ihren Pfad aus {@code PageNavigation.position}, im
     * Hintergrund. Ohne Space und ohne Pfad gibt es keinen Favoriten.
     */
    static void pageFavorite(AppContext context, String sourceId, String spaceKey, String pageId, String title,
                             Consumer<Favorites.Favorite> then) {
        Objects.requireNonNull(then, "then");
        Favorites store = context.favorites();
        if (spaceKey != null && store.contains(sourceId, spaceKey, pageId)) {
            then.accept(new Favorites.Favorite(sourceId, spaceKey, pageId, title, List.of(), List.of()));
            return;
        }
        String sourceTitle = context.sourceTitle(sourceId);
        AppRuntime runtime = context.runtime();
        Consumer<com.aresstack.spaceharvester.application.navigation.PageNavigation.Position> build = position -> {
            if (spaceKey != null || position != null && position.spaceKey() != null) {
                then.accept(Favorites.Favorite.page(sourceId, spaceKey, pageId, title, position, sourceTitle));
            }
        };
        if (runtime == null) {
            build.accept(null);
            return;
        }
        Background.run(() -> runtime.workspace().position(sourceId, pageId), build, error -> build.accept(null));
    }

    /** Für Tests: die Einträge eines Menüs als Text. */
    static List<String> labels(JComponent menu) {
        Component[] items = menu instanceof JMenu sub ? sub.getMenuComponents() : menu.getComponents();
        return java.util.Arrays.stream(items).map(c -> c instanceof JMenuItem item ? item.getText() : "—").toList();
    }
}
