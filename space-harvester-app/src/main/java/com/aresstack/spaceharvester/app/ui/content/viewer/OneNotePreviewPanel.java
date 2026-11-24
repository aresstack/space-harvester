package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.application.assets.OneNotePreview;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Component;

/** Display extracted note pages without inventing page boundaries. */
final class OneNotePreviewPanel extends JPanel {
    private final JList<OneNotePreview.Section> sections;
    private final JTextArea text = new JTextArea();

    OneNotePreviewPanel(OneNotePreview preview) {
        super(new BorderLayout());
        DefaultListModel<OneNotePreview.Section> model = new DefaultListModel<>();
        preview.sections().forEach(model::addElement);
        sections = new JList<>(model);
        sections.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sections.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list,
                        ((OneNotePreview.Section) value).label(), index, selected, focus);
            }
        });
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        sections.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                displaySelectedSection();
            }
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(sections), new JScrollPane(text));
        split.setDividerLocation(180);
        add(split, BorderLayout.CENTER);
        String notice = preview.hasPageBoundaries()
                ? "Extrahierter Text; kein originales OneNote-Layout."
                : "Der Parser liefert keine Seitengrenzen.";
        if (preview.outcome().truncated()) {
            notice += " Vorschau gekürzt.";
        }
        add(new JLabel(notice), BorderLayout.SOUTH);
        if (!model.isEmpty()) {
            selectSection(0);
        }
    }

    static String unavailableMessage(Outcome outcome) {
        if (outcome.reason().filter(reason -> reason == StatusReason.TEMP_SPACE_LOW).isPresent()) {
            return "OneNote kann momentan nicht angezeigt werden: Zu wenig freier Speicherplatz "
                    + "für temporäre Dateien. Gib Speicherplatz frei und öffne die Datei erneut. (temp-space-low)";
        }
        return "OneNote: kein lesbarer Text (" + outcome.status() + ", "
                + outcome.reason().map(StatusReason::code).orElse("-") + ")";
    }

    private void displaySelectedSection() {
        OneNotePreview.Section section = sections.getSelectedValue();
        text.setText(section == null ? "" : section.text());
        text.setCaretPosition(0);
    }

    int sectionCount() {
        return sections.getModel().getSize();
    }

    void selectSection(int index) {
        sections.setSelectedIndex(index);
    }

    String displayedText() {
        return text.getText();
    }
}
