package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;

import javax.swing.JComboBox;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Auswahl von Quelle und Space für Exporte und Suche. Die Space-Liste füllt sich aus der Verbindungsprüfung (beim
 * ersten Wählen einer Quelle automatisch); ein Space-Key lässt sich auch eintippen. Ohne einzelne Quelle („Alle
 * Quellen“ oder mehrere über {@link #spaceScope}) zeigt die Liste die Spaces aller betroffenen Quellen, bei mehr als
 * einer Quelle mit deren Namen.
 */
public final class SourcePicker {

    private final AppContext context;
    private final boolean anySource;
    private final boolean anySpace;
    private final JComboBox<Item> source = Comic.combo();
    private final JComboBox<Object> space = Comic.combo();
    private boolean filling;
    /** Quellen, deren Spaces ohne einzelne Quelle erscheinen; leer für alle. */
    private final Set<String> scope = new LinkedHashSet<>();

    /**
     * @param anySource ob „Alle Quellen“ wählbar ist
     * @param anySpace  ob der Space leer bleiben darf („Alle Spaces“)
     */
    public SourcePicker(AppContext context, boolean anySource, boolean anySpace) {
        this.context = context;
        this.anySource = anySource;
        this.anySpace = anySpace;
        space.setEditable(true);
        space.setToolTipText(anySpace ? "Space-Key; leer für alle Spaces" : "Space-Key; die Liste füllt sich nach der "
                + "Verbindungsprüfung");
        source.addActionListener(e -> sourceChanged());
        context.onChange(ignored -> refresh());
        refresh();
    }

    public JComboBox<Item> sourceBox() {
        return source;
    }

    public JComboBox<Object> spaceBox() {
        return space;
    }

    /** Die gewählte Quelle oder {@code null} („Alle Quellen“ bzw. keine). */
    public String sourceId() {
        Item item = (Item) source.getSelectedItem();
        return item == null ? null : item.id();
    }

    /** Der gewählte oder eingetippte Space-Key, leer wenn keiner. */
    public String spaceKey() {
        Object item = space.getEditor().getItem();
        if (item instanceof SpaceItem chosen) {
            return chosen.key();
        }
        String text = item == null ? "" : item.toString().trim();
        for (int i = 0; i < space.getItemCount(); i++) {
            if (space.getItemAt(i) instanceof SpaceItem known && known.toString().equals(text)) {
                return known.key();
            }
        }
        return text;
    }

    /**
     * Ohne einzelne Quelle: die Quellen, deren Spaces die Liste anbietet; leer für alle. Noch nicht geprüfte Quellen
     * werden dabei geprüft, damit ihre Spaces erscheinen.
     */
    public void spaceScope(Collection<String> sourceIds) {
        scope.clear();
        scope.addAll(sourceIds);
        for (String id : scopeIds()) {
            if (context.connection(id).state() == AppContext.Connection.State.NOT_CHECKED) {
                context.checkConnection(id, () -> fillSpaces(spaceKey()));
            }
        }
        fillSpaces(spaceKey());
    }

    public void select(String sourceId, String spaceKey) {
        for (int i = 0; i < source.getItemCount(); i++) {
            if (java.util.Objects.equals(source.getItemAt(i).id(), sourceId)) {
                source.setSelectedIndex(i);
                break;
            }
        }
        if (spaceKey != null) {
            show(spaceKey);
        }
    }

    /** Alle Quellen der laufenden Laufzeit. */
    public List<String> allSourceIds() {
        AppRuntime runtime = context.runtime();
        return runtime == null ? List.of() : runtime.workspace().sources().stream().map(HarvestSource::id).toList();
    }

    private void refresh() {
        String selected = sourceId();
        String key = spaceKey();
        filling = true;
        source.removeAllItems();
        if (anySource) {
            source.addItem(new Item(null, "Alle Quellen"));
        }
        AppRuntime runtime = context.runtime();
        for (HarvestSource s : runtime == null ? List.<HarvestSource>of() : runtime.workspace().sources()) {
            source.addItem(new Item(s.id(), s.title() == null || s.title().isBlank() ? s.id() : s.title()));
        }
        filling = false;
        select(selected, null);
        fillSpaces(key);
    }

    private void sourceChanged() {
        if (filling) {
            return;
        }
        String id = sourceId();
        if (id != null && context.connection(id).state() == AppContext.Connection.State.NOT_CHECKED) {
            context.checkConnection(id, () -> fillSpaces(spaceKey()));
        }
        fillSpaces(spaceKey());
    }

    private List<String> scopeIds() {
        String id = sourceId();
        if (id != null) {
            return List.of(id);
        }
        if (!anySource) {
            return List.of();
        }
        List<String> all = allSourceIds();
        return scope.isEmpty() ? all : all.stream().filter(scope::contains).toList();
    }

    private void fillSpaces(String keep) {
        List<String> ids = scopeIds();
        boolean several = ids.size() > 1;
        filling = true;
        space.removeAllItems();
        if (anySpace) {
            space.addItem("");
        }
        AppRuntime runtime = context.runtime();
        for (String id : ids) {
            String title = runtime == null ? id : runtime.workspace().sources().stream()
                    .filter(s -> s.id().equals(id)).findFirst()
                    .map(s -> s.title() == null || s.title().isBlank() ? s.id() : s.title()).orElse(id);
            for (Partition partition : context.connection(id).spaces()) {
                space.addItem(new SpaceItem(partition.key(), partition.name(), several ? title : null));
            }
        }
        filling = false;
        show(keep == null ? "" : keep);
    }

    /** Zeigt einen Key: den passenden Eintrag, wenn es genau einen gibt, sonst den Key als Text. */
    private void show(String key) {
        List<SpaceItem> matches = new ArrayList<>();
        for (int i = 0; i < space.getItemCount(); i++) {
            if (space.getItemAt(i) instanceof SpaceItem item && item.key().equals(key)) {
                matches.add(item);
            }
        }
        Object shown = matches.isEmpty() ? key : matches.get(0);
        space.setSelectedItem(shown);
        space.getEditor().setItem(shown);
    }

    /**
     * Ein Space der Liste: Key, Name und, wenn mehrere Quellen betroffen sind, die Quelle. Gefiltert wird nach dem
     * Key; derselbe Key in zwei Quellen filtert in beiden.
     */
    public record SpaceItem(String key, String name, String source) {
        @Override
        public String toString() {
            String label = name == null || name.isBlank() || name.equals(key) ? key : key + " · " + name;
            return source == null ? label : label + " (" + source + ")";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SpaceItem item && item.key.equals(key) && Objects.equals(item.source, source);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, source);
        }
    }

    /** Eintrag der Quellenauswahl; {@code id == null} für „Alle Quellen“. */
    public record Item(String id, String title) {
        @Override
        public String toString() {
            return title;
        }
    }
}
