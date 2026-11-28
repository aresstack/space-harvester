package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.application.assets.OneNotePreview;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Component;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/** Display extracted note pages without inventing page boundaries. */
final class OneNotePreviewPanel extends JPanel {
    private final JList<OneNotePreview.Section> sections;
    private final JTextArea text = new JTextArea();

    OneNotePreviewPanel(OneNotePreview preview) {
        super(new BorderLayout());
        setBackground(Comic.SURFACE);
        DefaultListModel<OneNotePreview.Section> model = new DefaultListModel<>();
        preview.sections().forEach(model::addElement);
        sections = new JList<>(model);
        sections.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sections.setFont(Comic.regular(13f));
        sections.setBackground(Comic.SURFACE);
        sections.setForeground(Comic.TEXT);
        sections.setSelectionBackground(Comic.PALETTE.getAccentYellow());
        sections.setSelectionForeground(Comic.INK);
        sections.setFixedCellHeight(32);
        // Keep horizontal navigation inside the notebook while its page list has focus.
        bindPageNavigation("LEFT", -1);
        bindPageNavigation("RIGHT", 1);
        sections.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list,
                        ((OneNotePreview.Section) value).label(), index, selected, focus);
                label.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
                label.setFont(selected ? Comic.semiBold(13f) : Comic.regular(13f));
                return label;
            }
        });
        text.setEditable(false);
        text.setFont(Comic.regular(14f));
        text.setForeground(Comic.TEXT);
        text.setBackground(AppColors.of("comic.fieldBg"));
        text.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        sections.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                displaySelectedSection();
            }
        });
        JScrollPane document = Comic.scroll(text);
        document.getViewport().setBackground(AppColors.of("comic.fieldBg"));
        if (preview.hasPageBoundaries()) {
            ComicSplitPane split = new ComicSplitPane(Comic.scroll(sections), document, 140, 360, Comic.PALETTE);
            split.setBackground(Comic.SURFACE);
            split.setPreferredLeftWidth(200);
            add(split, BorderLayout.CENTER);
        } else {
            // Use the entire available width when the parser cannot recover the page structure.
            add(document, BorderLayout.CENTER);
        }
        String notice = preview.hasPageBoundaries()
                ? "Textvorschau – Originalformatierung nicht verfügbar."
                : "Textvorschau – Seitenstruktur dieser Datei konnte nicht gelesen werden.";
        if (preview.outcome().truncated()) {
            notice += " Vorschau gekürzt.";
        }
        JLabel hint = Comic.muted(notice);
        hint.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        add(hint, BorderLayout.SOUTH);
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

    private void bindPageNavigation(String key, int direction) {
        String action = "note-page-" + key;
        sections.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), action);
        sections.getActionMap().put(action, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                int target = sections.getSelectedIndex() + direction;
                if (target >= 0 && target < sectionCount()) {
                    selectSection(target);
                    sections.ensureIndexIsVisible(target);
                }
            }
        });
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
