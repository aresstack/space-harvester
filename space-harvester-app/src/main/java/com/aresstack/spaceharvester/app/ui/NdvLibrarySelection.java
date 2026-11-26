package com.aresstack.spaceharvester.app.ui;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.*;
import java.util.stream.Collectors;

/** Present server-provided libraries while retaining configured legacy filters and DDM scopes. */
final class NdvLibrarySelection {

    private NdvLibrarySelection() {
    }

    static void show(JComponent anchor, JTextField target, List<String> names) {
        Set<String> selected = selections(target.getText());
        JPopupMenu popup = new JPopupMenu();
        JPanel items = new JPanel();
        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        Map<String, JCheckBox> choices = new LinkedHashMap<>();
        Set<String> available = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        available.addAll(names);
        available.addAll(selected);
        for (String name : available) {
            JCheckBox check = new JCheckBox(name, selected.contains(name));
            choices.put(name, check);
            items.add(check);
        }
        JScrollPane scroll = new JScrollPane(items);
        scroll.setPreferredSize(new Dimension(Math.max(260, anchor.getWidth()), 250));
        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        JButton apply = new JButton("Übernehmen");
        apply.addActionListener(event -> {
            target.setText(choices.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                    .map(Map.Entry::getKey).collect(Collectors.joining(", ")));
            popup.setVisible(false);
        });
        content.add(apply, BorderLayout.SOUTH);
        popup.add(content);
        popup.show(anchor, 0, anchor.getHeight());
    }

    static Set<String> selections(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String part : value.split("[;,\\r\\n]+")) {
            if (!part.isBlank()) {
                result.add(part.strip().toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }
}