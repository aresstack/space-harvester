package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.Extension;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.ExtensionMode;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.Option;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.Report;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns.ReportMode;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * Die Felder der BetaView-Suche mit Auswahlliste wie in MainframeMate: oben die Liste, darunter (nur wenn nötig) die
 * Eingaben zur gewählten Art. Listen und Regeln stehen im Fachmodell ({@link FilterPatterns}); hier nur die Oberfläche.
 * Die Listen zeigen beim Aufklappen die vollen Texte, auch wenn das Feld schmaler ist.
 */
final class PatternFields {

    /** Breite der Listen im Formular; längere Texte stehen im aufgeklappten Menü und im Tooltip. */
    private static final int COMBO_WIDTH = 250;

    private PatternFields() {
    }

    /** Ein Feld, das einen Suchwert liefert und einen gespeicherten wieder einstellt. */
    interface Field {
        String value();

        void apply(String value);

        JComponent component();
    }

    /** „Liste oder frei“: Form, Jobname, Ordner, Fach, Empfänger. */
    static final class ChoiceField extends JPanel implements Field {

        final JComboBox<Option> box;
        final PlaceholderField custom = new PlaceholderField(14, "Freier Wert");
        private final List<Option> options;
        private final boolean emptyAsWildcard;

        ChoiceField(List<Option> options, boolean emptyAsWildcard) {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            this.options = options;
            this.emptyAsWildcard = emptyAsWildcard;
            box = combo(options);
            add(box, BorderLayout.NORTH);
            add(custom, BorderLayout.CENTER);
            box.addActionListener(e -> update());
            update();
        }

        private void update() {
            custom.setVisible(FilterPatterns.CUSTOM.equals(selected(box)));
            revalidate();
        }

        @Override
        public String value() {
            return FilterPatterns.choice(selected(box), custom.getText(), emptyAsWildcard);
        }

        @Override
        public void apply(String value) {
            String[] parts = FilterPatterns.parseChoice(options, value);
            select(box, parts[0]);
            custom.setText(parts[1]);
            update();
        }

        @Override
        public JComponent component() {
            return this;
        }
    }

    /** EXTENSION wie {@code ExtensionFilterPanel}. */
    static final class ExtensionField extends JPanel implements Field {

        final JComboBox<ExtensionMode> mode = Comic.combo();
        final PlaceholderField bkNumber = new PlaceholderField(3, "nn");
        final PlaceholderField bkSuffix = new PlaceholderField(10, "Suffix");
        final PlaceholderField bankNumber = new PlaceholderField(3, "nn");
        final PlaceholderField bankSuffix = new PlaceholderField(6, "Suffix");
        final JComboBox<Option> constant = combo(FilterPatterns.EXTENSION_CONSTANTS);
        final PlaceholderField bewFb = new PlaceholderField(10, "BEW-NR");
        final PlaceholderField bewLb = new PlaceholderField(10, "BEW-NR");
        final PlaceholderField custom = new PlaceholderField(18, "Freier Wert");
        private final CardLayout cards = new CardLayout();
        private final JPanel detail = new JPanel(cards);

        ExtensionField() {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            for (ExtensionMode value : ExtensionMode.values()) {
                mode.addItem(value);
            }
            narrow(mode);
            detail.setOpaque(false);
            detail.add(line(Comic.text("BK"), bkNumber, Comic.text("Suffix"), bkSuffix), ExtensionMode.BK_FREE.name());
            detail.add(line(Comic.text("BK"), bankNumber, Comic.text("BANK"), Comic.text("Suffix"), bankSuffix),
                    ExtensionMode.BK_BANK.name());
            detail.add(line(constant), ExtensionMode.CONSTANT.name());
            detail.add(line(Comic.text("B"), bewFb, Comic.text("F13FB")), ExtensionMode.BEW_FB.name());
            detail.add(line(Comic.text("B"), bewLb, Comic.text("F13LB")), ExtensionMode.BEW_LB.name());
            detail.add(line(custom), ExtensionMode.CUSTOM.name());
            add(mode, BorderLayout.NORTH);
            add(detail, BorderLayout.CENTER);
            mode.addActionListener(e -> showCard());
            showCard();
        }

        private void showCard() {
            cards.show(detail, ((ExtensionMode) mode.getSelectedItem()).name());
        }

        @Override
        public String value() {
            ExtensionMode chosen = (ExtensionMode) mode.getSelectedItem();
            return switch (chosen) {
                case BK_FREE -> new Extension(chosen, bkNumber.getText(), bkSuffix.getText()).value();
                case BK_BANK -> new Extension(chosen, bankNumber.getText(), bankSuffix.getText()).value();
                case CONSTANT -> new Extension(chosen, selected(constant), "").value();
                case BEW_FB -> new Extension(chosen, bewFb.getText(), "").value();
                case BEW_LB -> new Extension(chosen, bewLb.getText(), "").value();
                case CUSTOM -> new Extension(chosen, custom.getText(), "").value();
            };
        }

        @Override
        public void apply(String value) {
            Extension parts = Extension.parse(value);
            mode.setSelectedItem(parts.mode());
            switch (parts.mode()) {
                case BK_FREE -> {
                    bkNumber.setText(parts.first());
                    bkSuffix.setText(parts.suffix());
                }
                case BK_BANK -> {
                    bankNumber.setText(parts.first());
                    bankSuffix.setText(parts.suffix());
                }
                case CONSTANT -> select(constant, parts.first());
                case BEW_FB -> bewFb.setText(parts.first());
                case BEW_LB -> bewLb.setText(parts.first());
                case CUSTOM -> custom.setText(parts.first());
            }
            showCard();
        }

        @Override
        public JComponent component() {
            return this;
        }
    }

    /** REPORT wie {@code ReportFilterPanel}. */
    static final class ReportField extends JPanel implements Field {

        final JComboBox<ReportMode> mode = Comic.combo();
        final JComboBox<Option> prefix = combo(FilterPatterns.REPORT_PREFIXES);
        final JComboBox<Option> baList = combo(FilterPatterns.BA_LISTS);
        final JComboBox<Option> baType = Comic.combo();
        final JComboBox<Option> kList = combo(FilterPatterns.K_LISTS);
        final PlaceholderField kDigits = new PlaceholderField(4, "nnn");
        final JComboBox<Option> kType = combo(FilterPatterns.K_TYPES);
        final PlaceholderField bewirt = new PlaceholderField(10, "Bewirtschafternummer");
        final PlaceholderField custom = new PlaceholderField(18, "Freier Wert");
        private final javax.swing.JLabel bewirtPreview = Comic.muted("→ *");
        private final CardLayout cards = new CardLayout();
        private final JPanel detail = new JPanel(cards);

        ReportField() {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            for (ReportMode value : ReportMode.values()) {
                mode.addItem(value);
            }
            narrow(mode);
            baTypes("BA001");
            baList.addActionListener(e -> baTypes(selected(baList)));
            kDigits.setToolTipText("Oder drei Ziffern direkt eingeben");
            bewirt.setToolTipText("Bewirtschafternummer eingeben (wird automatisch mit * umschlossen)");
            bewirt.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    preview();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    preview();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    preview();
                }
            });
            detail.setOpaque(false);
            detail.add(line(Comic.muted("(*)")), ReportMode.ANY.name());
            detail.add(line(prefix), ReportMode.PREFIX.name());
            JPanel ba = new JPanel(new BorderLayout(0, 4));
            ba.setOpaque(false);
            ba.add(baList, BorderLayout.NORTH);
            ba.add(baType, BorderLayout.CENTER);
            detail.add(ba, ReportMode.BA.name());
            JPanel k = new JPanel(new BorderLayout(0, 4));
            k.setOpaque(false);
            k.add(line(Comic.text("K9999BA"), kList), BorderLayout.NORTH);
            k.add(line(Comic.text("oder Ziffern"), kDigits, Comic.text("Typ"), kType), BorderLayout.CENTER);
            detail.add(k, ReportMode.K9999.name());
            detail.add(line(bewirt, bewirtPreview), ReportMode.BEWIRT.name());
            detail.add(line(custom), ReportMode.CUSTOM.name());
            add(mode, BorderLayout.NORTH);
            add(detail, BorderLayout.CENTER);
            mode.addActionListener(e -> showCard());
            showCard();
            preview();
        }

        private void baTypes(String list) {
            baType.removeAllItems();
            for (Option option : FilterPatterns.baTypes(list)) {
                baType.addItem(option);
            }
            narrow(baType);
            if (baType.getItemCount() > 0) {
                baType.setSelectedIndex(0);
            }
        }

        private void preview() {
            String nr = bewirt.getText().trim();
            bewirtPreview.setText(nr.isEmpty() ? "→ *" : "→ %" + nr + "*");
        }

        private void showCard() {
            cards.show(detail, ((ReportMode) mode.getSelectedItem()).name());
        }

        @Override
        public String value() {
            ReportMode chosen = (ReportMode) mode.getSelectedItem();
            return switch (chosen) {
                case ANY -> new Report(chosen, "", "").value();
                case PREFIX -> new Report(chosen, selected(prefix), "").value();
                case BA -> new Report(chosen, selected(baList), selected(baType)).value();
                case K9999 -> {
                    // Wie MainframeMate: drei eingetippte Ziffern gehen vor, sonst die Liste.
                    String digits = kDigits.getText().replaceAll("[^0-9]", "");
                    yield new Report(chosen, digits.length() == 3 ? digits : selected(kList), selected(kType)).value();
                }
                case BEWIRT -> new Report(chosen, bewirt.getText(), "").value();
                case CUSTOM -> new Report(chosen, custom.getText(), "").value();
            };
        }

        @Override
        public void apply(String value) {
            Report parts = Report.parse(value);
            mode.setSelectedItem(parts.mode());
            switch (parts.mode()) {
                case ANY -> { }
                case PREFIX -> select(prefix, parts.first());
                case BA -> {
                    select(baList, parts.first());
                    baTypes(parts.first());
                    select(baType, parts.type());
                }
                case K9999 -> {
                    kDigits.setText(parts.first());
                    select(kType, parts.type());
                }
                case BEWIRT -> bewirt.setText(parts.first());
                case CUSTOM -> custom.setText(parts.first());
            }
            showCard();
        }

        @Override
        public JComponent component() {
            return this;
        }
    }

    // ---- Hilfen ----

    static JComboBox<Option> combo(List<Option> options) {
        JComboBox<Option> box = Comic.combo();
        for (Option option : options) {
            box.addItem(option);
        }
        narrow(box);
        return box;
    }

    /**
     * Hält eine Liste schmal genug für das Formular (bei 960 px), zeigt aber beim Aufklappen die vollen Texte und den
     * gewählten Text als Tooltip.
     */
    static void narrow(JComboBox<?> box) {
        Dimension preferred = box.getPreferredSize();
        int width = Math.min(preferred.width, COMBO_WIDTH);
        box.setPreferredSize(new Dimension(width, preferred.height));
        box.setMinimumSize(new Dimension(Math.min(width, 120), preferred.height));
        Runnable tip = () -> box.setToolTipText(box.getSelectedItem() == null ? null
                : box.getSelectedItem().toString());
        tip.run();
        if (box.getClientProperty("bv.narrow") == null) {
            box.putClientProperty("bv.narrow", Boolean.TRUE);
            box.addActionListener(e -> tip.run());
            box.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    Object child = box.getUI().getAccessibleChild(box, 0);
                    if (child instanceof JPopupMenu popup && popup.getComponentCount() > 0
                            && popup.getComponent(0) instanceof JScrollPane scroll) {
                        int full = scroll.getViewport().getView().getPreferredSize().width
                                + scroll.getVerticalScrollBar().getPreferredSize().width + 8;
                        int popupWidth = Math.max(box.getWidth(), full);
                        Dimension size = scroll.getPreferredSize();
                        scroll.setPreferredSize(new Dimension(popupWidth, size.height));
                        scroll.setMaximumSize(new Dimension(popupWidth, size.height));
                        popup.setPopupSize(popupWidth, popup.getPreferredSize().height);
                    }
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                }
            });
        }
    }

    static String selected(JComboBox<?> box) {
        Object item = box.getSelectedItem();
        return item instanceof Option option ? option.value() : "";
    }

    static void select(JComboBox<Option> box, String value) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).value().equals(value)) {
                box.setSelectedIndex(i);
                return;
            }
        }
    }

    private static JPanel line(JComponent... parts) {
        // Bündig mit der Liste darüber: kein Abstand vor dem ersten Teil.
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        line.setOpaque(false);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                line.add(javax.swing.Box.createHorizontalStrut(6));
            }
            line.add(parts[i]);
        }
        return line;
    }
}
