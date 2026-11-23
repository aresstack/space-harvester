package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.deck.NdvAnalysisSettings;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAccessRules;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/**
 * Einstellungen › NDV, Teil Analyse wie {@code NdvSettingsPanel} und die NDV-Gruppe der Sicherheitsfilter in
 * MainframeMate: Bibliotheks-Suchreihenfolge, Zuordnung STEPLIB → NDV und die Sperr- und Freigabeliste. Jede Änderung
 * gilt sofort.
 */
final class NdvAnalysisSettingsPanel {

    static final String ORDER_HEADING = "Bibliotheks-Suchreihenfolge";
    static final String STEPLIB_HEADING = "Natural-Bibliothekszuordnung (STEPLIB → NDV)";
    static final String RULES_HEADING = "NDV-Sperrliste und Freigaben";

    final DefaultListModel<String> order = new DefaultListModel<>();
    final JList<String> orderList = new JList<>(order);
    final PlaceholderField orderField = new PlaceholderField(14, "Bibliothek, z. B. LAGER");
    final DefaultTableModel stepLibs = new DefaultTableModel(new Object[] {"STEPLIB (JCL)", "NDV-Bibliothek"}, 0);
    final JTable stepLibTable = Comic.table(new JTable(stepLibs));
    final PlaceholderField stepField = new PlaceholderField(10, "STEPLIB");
    final PlaceholderField libField = new PlaceholderField(10, "NDV-Bibliothek");
    final JCheckBox blacklistAll = new JCheckBox("Alles sperren – nur Freigegebenes wird zwischengespeichert");
    final DefaultTableModel rules = new DefaultTableModel(new Object[] {"", "Pfad", "Liste"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : Object.class;
        }
    };
    final JTable rulesTable = Comic.table(new JTable(rules));
    final PlaceholderField pathField = new PlaceholderField(20, "ndv://BIBLIOTHEK oder ndv://BIBLIOTHEK/OBJEKT");
    private boolean filling;

    NdvAnalysisSettingsPanel() {
        NdvAnalysisSettings.searchOrder().forEach(order::addElement);
        NdvAnalysisSettings.stepLibs().forEach((step, lib) -> stepLibs.addRow(new Object[] {step, lib}));
        stepLibs.addTableModelListener(e -> {
            if (!filling) {
                saveStepLibs();
            }
        });
        NdvAnalysisSettings.rules().observe(() -> SwingUtilities.invokeLater(this::fillRules));
        fillRules();
        blacklistAll.setOpaque(false);
        blacklistAll.setFont(Comic.regular(13f));
        blacklistAll.addActionListener(e -> NdvAnalysisSettings.rules().blacklistAll(blacklistAll.isSelected()));
    }

    // ---- Suchreihenfolge ------------------------------------------------------------------------------------------

    JComponent orderComponent() {
        orderList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        orderList.setVisibleRowCount(5);
        orderList.setFont(Comic.regular(13f));
        orderList.setSelectionBackground(Comic.YELLOW);
        orderList.getAccessibleContext().setAccessibleName(ORDER_HEADING);
        Comic.style(orderField);
        InputFrame frame = new InputFrame(orderField);
        frame.addButton(InputFrame.flatButton("Hinzufügen", "Bibliothek ans Ende der Reihenfolge setzen (Eingabe)"))
                .addActionListener(e -> addLibrary());
        orderField.addActionListener(e -> addLibrary());
        ComposerButton up = Comic.button("Nach oben", "Gewählte Bibliothek früher durchsuchen");
        ComposerButton down = Comic.button("Nach unten", "Gewählte Bibliothek später durchsuchen");
        ComposerButton remove = Comic.button("Entfernen", "Gewählte Bibliothek aus der Reihenfolge nehmen");
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));
        remove.addActionListener(e -> removeLibrary());
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.add(Comic.wrapped("Ein Name ohne Bibliothek (etwa aus einem CALLNAT) wird erst in der eigenen, dann in der "
                + "Standard-Bibliothek und danach in dieser Reihenfolge gesucht.", Comic.MUTED, 12.5f),
                BorderLayout.NORTH);
        javax.swing.JScrollPane scroll = Comic.scroll(orderList);
        scroll.setPreferredSize(new Dimension(0, 110));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout(8, 0));
        foot.setOpaque(false);
        foot.add(frame, BorderLayout.CENTER);
        foot.add(Comic.row(up, down, remove), BorderLayout.EAST);
        panel.add(foot, BorderLayout.SOUTH);
        return panel;
    }

    void addLibrary() {
        String lib = orderField.getText().trim().toUpperCase(Locale.ROOT);
        if (!lib.isEmpty() && !order.contains(lib)) {
            order.addElement(lib);
            saveOrder();
        }
        orderField.setText("");
    }

    void move(int delta) {
        int index = orderList.getSelectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= order.size()) {
            return;
        }
        String value = order.remove(index);
        order.add(target, value);
        orderList.setSelectedIndex(target);
        saveOrder();
    }

    void removeLibrary() {
        int index = orderList.getSelectedIndex();
        if (index >= 0) {
            order.remove(index);
            saveOrder();
        }
    }

    private void saveOrder() {
        List<String> libs = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            libs.add(order.get(i));
        }
        NdvAnalysisSettings.searchOrder(libs);
    }

    // ---- STEPLIB --------------------------------------------------------------------------------------------------

    JComponent stepLibComponent() {
        stepLibTable.getAccessibleContext().setAccessibleName(STEPLIB_HEADING);
        stepLibTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Comic.style(stepField);
        Comic.style(libField);
        ComposerButton add = Comic.button("Hinzufügen", "Zuordnung aufnehmen");
        ComposerButton remove = Comic.button("Entfernen", "Gewählte Zuordnung entfernen");
        add.addActionListener(e -> addStepLib());
        libField.addActionListener(e -> addStepLib());
        remove.addActionListener(e -> removeStepLib());
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.add(Comic.wrapped("Ordnet JCL-STEPLIB-Namen der NDV-Bibliothek zu (z. B. ABAK-M → ABAK-T). Gilt beim "
                + "Öffnen von Natural-Programmen aus der JCL; fehlt eine Zuordnung, fragt die App einmal und merkt sie.",
                Comic.MUTED, 12.5f), BorderLayout.NORTH);
        javax.swing.JScrollPane scroll = Comic.scroll(stepLibTable);
        scroll.setPreferredSize(new Dimension(0, 120));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout(8, 0));
        foot.setOpaque(false);
        JPanel fields = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        fields.setOpaque(false);
        fields.add(new InputFrame(stepField));
        fields.add(new InputFrame(libField));
        foot.add(fields, BorderLayout.CENTER);
        foot.add(Comic.row(add, remove), BorderLayout.EAST);
        panel.add(foot, BorderLayout.SOUTH);
        return panel;
    }

    void addStepLib() {
        String step = stepField.getText().trim().toUpperCase(Locale.ROOT);
        String lib = libField.getText().trim().toUpperCase(Locale.ROOT);
        if (step.isEmpty() || lib.isEmpty()) {
            return;
        }
        for (int row = 0; row < stepLibs.getRowCount(); row++) {
            if (step.equals(stepLibs.getValueAt(row, 0))) {
                stepLibs.setValueAt(lib, row, 1);
                stepField.setText("");
                libField.setText("");
                return;
            }
        }
        stepLibs.addRow(new Object[] {step, lib});
        stepField.setText("");
        libField.setText("");
    }

    void removeStepLib() {
        int row = stepLibTable.getSelectedRow();
        if (row >= 0) {
            if (stepLibTable.isEditing()) {
                stepLibTable.getCellEditor().stopCellEditing();
            }
            stepLibs.removeRow(stepLibTable.convertRowIndexToModel(row));
        }
    }

    private void saveStepLibs() {
        Map<String, String> map = new LinkedHashMap<>();
        for (int row = 0; row < stepLibs.getRowCount(); row++) {
            map.put(String.valueOf(stepLibs.getValueAt(row, 0)), String.valueOf(stepLibs.getValueAt(row, 1)));
        }
        NdvAnalysisSettings.stepLibs(map);
    }

    // ---- Sperrliste und Freigaben ---------------------------------------------------------------------------------

    JComponent rulesComponent() {
        rulesTable.getAccessibleContext().setAccessibleName(RULES_HEADING);
        rulesTable.getColumnModel().getColumn(0).setMaxWidth(40);
        rulesTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        rulesTable.getColumnModel().getColumn(2).setMaxWidth(140);
        Comic.style(pathField);
        InputFrame frame = new InputFrame(pathField);
        frame.addButton(InputFrame.flatButton("Sperren", "Pfad auf die Sperrliste setzen")).addActionListener(
                e -> addRule(true));
        frame.addButton(InputFrame.flatButton("Freigeben", "Pfad auf die Freigabeliste setzen")).addActionListener(
                e -> addRule(false));
        ComposerButton removeChecked = Comic.danger("Angehakte entfernen", "Angehakte Einträge aus beiden Listen nehmen");
        removeChecked.addActionListener(e -> removeChecked());
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(Comic.wrapped("Gesperrte Bibliotheken und Objekte werden nicht zwischengespeichert und nicht analysiert; "
                + "„Gesperrte ausblenden“ im NDV-Bereich blendet sie aus. Die Sperrliste gewinnt immer.", Comic.MUTED,
                12.5f), BorderLayout.NORTH);
        head.add(blacklistAll, BorderLayout.SOUTH);
        panel.add(head, BorderLayout.NORTH);
        javax.swing.JScrollPane scroll = Comic.scroll(rulesTable);
        scroll.setPreferredSize(new Dimension(0, 130));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout(8, 0));
        foot.setOpaque(false);
        foot.add(frame, BorderLayout.CENTER);
        foot.add(Comic.row(removeChecked), BorderLayout.EAST);
        panel.add(foot, BorderLayout.SOUTH);
        return panel;
    }

    void addRule(boolean block) {
        String path = pathField.getText().trim();
        if (path.isEmpty()) {
            return;
        }
        if (!path.toLowerCase(Locale.ROOT).startsWith("ndv://")) {
            path = "ndv://" + path;
        }
        if (block) {
            NdvAnalysisSettings.rules().blacklist(List.of(path));
        } else {
            NdvAnalysisSettings.rules().whitelist(List.of(path));
        }
        pathField.setText("");
        fillRules();
    }

    void removeChecked() {
        List<String> paths = new ArrayList<>();
        for (int row = 0; row < rules.getRowCount(); row++) {
            if (Boolean.TRUE.equals(rules.getValueAt(row, 0))) {
                paths.add(String.valueOf(rules.getValueAt(row, 1)));
            }
        }
        if (!paths.isEmpty()) {
            NdvAnalysisSettings.rules().remove(paths);
            fillRules();
        }
    }

    void fillRules() {
        NdvAccessRules current = NdvAnalysisSettings.rules();
        filling = true;
        try {
            rules.setRowCount(0);
            current.blacklistEntries().forEach(path -> rules.addRow(new Object[] {false, path, "Gesperrt"}));
            current.whitelistEntries().forEach(path -> rules.addRow(new Object[] {false, path, "Freigegeben"}));
            blacklistAll.setSelected(current.blacklistAll());
        } finally {
            filling = false;
        }
    }
}
