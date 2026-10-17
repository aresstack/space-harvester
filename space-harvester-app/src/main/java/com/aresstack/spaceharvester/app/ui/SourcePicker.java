package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;

import javax.swing.JComboBox;
import java.util.List;

/**
 * Auswahl von Quelle und Space für Exporte und Suche. Die Space-Liste füllt sich aus der Verbindungsprüfung (beim
 * ersten Wählen einer Quelle automatisch); ein Space-Key lässt sich auch eintippen.
 */
public final class SourcePicker {

    private final AppContext context;
    private final boolean anySource;
    private final boolean anySpace;
    private final JComboBox<Item> source = Comic.combo();
    private final JComboBox<String> space = Comic.combo();
    private boolean filling;

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

    public JComboBox<String> spaceBox() {
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
        return item == null ? "" : item.toString().trim();
    }

    public void select(String sourceId, String spaceKey) {
        for (int i = 0; i < source.getItemCount(); i++) {
            if (java.util.Objects.equals(source.getItemAt(i).id(), sourceId)) {
                source.setSelectedIndex(i);
                break;
            }
        }
        if (spaceKey != null) {
            space.setSelectedItem(spaceKey);
            space.getEditor().setItem(spaceKey);
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

    private void fillSpaces(String keep) {
        space.removeAllItems();
        if (anySpace) {
            space.addItem("");
        }
        String id = sourceId();
        if (id != null) {
            for (Partition partition : context.connection(id).spaces()) {
                space.addItem(partition.key());
            }
        }
        space.setSelectedItem(keep == null ? "" : keep);
        space.getEditor().setItem(keep == null ? "" : keep);
    }

    /** Eintrag der Quellenauswahl; {@code id == null} für „Alle Quellen“. */
    public record Item(String id, String title) {
        @Override
        public String toString() {
            return title;
        }
    }
}
