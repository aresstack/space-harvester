package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.BackButton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Keep one chronological timeline for all navigable workspace locations. */
final class WorkspaceHistory<T> implements BackButton.Navigator {
    private final List<T> entries = new ArrayList<>();
    private final Function<T, String> label;
    private final Consumer<T> restore;
    private int index = -1;
    private boolean preview;
    private boolean restoring;

    WorkspaceHistory(Function<T, String> label, Consumer<T> restore) {
        this.label = Objects.requireNonNull(label);
        this.restore = Objects.requireNonNull(restore);
    }

    void visit(T location) {
        if (restoring) {
            return;
        }
        preview = false;
        if (index >= 0 && Objects.equals(entries.get(index), location)) {
            return;
        }
        entries.subList(index + 1, entries.size()).clear();
        entries.add(location);
        index = entries.size() - 1;
    }

    /** Allow returning from an unsubmitted search without manufacturing a search result. */
    void preview() {
        if (!restoring && index >= 0) {
            preview = true;
        }
    }

    boolean restoring() {
        return restoring;
    }

    @Override
    public BackButton.History history() {
        List<String> back = new ArrayList<>();
        List<String> forward = new ArrayList<>();
        for (int i = preview ? index : index - 1; i >= 0; i--) {
            back.add(label.apply(entries.get(i)));
        }
        for (int i = index + 1; i < entries.size(); i++) {
            forward.add(label.apply(entries.get(i)));
        }
        return new BackButton.History(forward, back);
    }

    @Override
    public void go(int steps) {
        int target = index + steps + (preview && steps < 0 ? 1 : 0);
        if (steps == 0 || target < 0 || target >= entries.size() || restoring) {
            return;
        }
        restoring = true;
        try {
            restore.accept(entries.get(target));
            index = target;
            preview = false;
        } finally {
            restoring = false;
        }
    }
}
