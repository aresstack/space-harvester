package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.deck.ftp.DatasetAttributes;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Locale;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Formular „Neues Mainframe-Dataset anlegen“ wie in MainframeMate: oben der aktuelle Kontext, darunter der neue
 * Qualifier mit Vorschau des vollen Namens (mit Punkt eingegeben gilt er als voller Name), dann DSORG, RECFM, LRECL,
 * BLKSIZE, Primär- und Sekundärspuren und Directory-Blöcke (nur für PO). Vorgaben wie dort.
 */
final class NewDatasetForm extends JPanel {

    final PlaceholderField name = new PlaceholderField(22, "nur der neue Teil, etwa NEU");
    final JComboBox<DatasetAttributes.Organization> organization = Comic.combo();
    final JComboBox<String> recordFormat = Comic.combo();
    final PlaceholderField recordLength = new PlaceholderField(6, "80");
    final PlaceholderField blockSize = new PlaceholderField(6, "27920");
    final PlaceholderField primary = new PlaceholderField(6, "10");
    final PlaceholderField secondary = new PlaceholderField(6, "5");
    final PlaceholderField directory = new PlaceholderField(6, "10");
    private final JLabel preview = Comic.muted(" ");
    private final JLabel problem = new JLabel(" ");
    private final String context;

    NewDatasetForm(String context) {
        super(new GridBagLayout());
        this.context = context == null ? "" : context;
        setOpaque(false);
        for (DatasetAttributes.Organization value : DatasetAttributes.Organization.values()) {
            organization.addItem(value);
        }
        organization.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof DatasetAttributes.Organization org) {
                    setText(org.label());
                }
                return this;
            }
        });
        for (String format : new String[] {"FB", "VB", "F", "V", "FBA", "VBA", "U"}) {
            recordFormat.addItem(format);
        }
        organization.addActionListener(e -> directory.setEnabled(
                organization.getSelectedItem() == DatasetAttributes.Organization.PO));
        name.setToolTipText("<html>Nur den neuen Teil eingeben (z.B. <b>NEW</b>)<br>"
                + "Für vollständigen Pfad mit Punkt eingeben (z.B. <b>USR1.KOMPLETT.NEU</b>)</html>");
        name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updatePreview();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updatePreview();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updatePreview();
            }
        });
        recordLength.setToolTipText("Satzlänge 1–32760");
        blockSize.setToolTipText("Blockgröße 0–32760; 0 = vom System bestimmt");
        primary.setToolTipText("Primärzuordnung in Spuren (TRACKS)");
        secondary.setToolTipText("Sekundärzuordnung in Spuren");
        directory.setToolTipText("Directory-Blöcke, nur für PO");
        preview.setForeground(Comic.GREEN);
        problem.setFont(Comic.regular(12.5f));
        problem.setForeground(Comic.RED);

        JLabel heading = Comic.text(this.context.isEmpty() ? "Kein Kontext – bitte vollständigen Dataset-Namen eingeben"
                : "Aktueller Kontext: " + this.context);
        heading.setFont(Comic.semiBold(13f));
        int y = 0;
        full(heading, y++);
        row("Neuer Qualifier", name, y++);
        row("", preview, y++);
        row("DSORG", organization, y++);
        row("RECFM", recordFormat, y++);
        row("LRECL", recordLength, y++);
        row("BLKSIZE", blockSize, y++);
        row("Primär (Spuren)", primary, y++);
        row("Sekundär (Spuren)", secondary, y++);
        row("Directory-Blöcke", directory, y++);
        full(problem, y);
        getAccessibleContext().setAccessibleName("Neues Dataset anlegen");
    }

    private void full(JComponent component, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 6, 0);
        add(component, c);
    }

    private void row(String label, JComponent field, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 10);
        JLabel text = Comic.text(label);
        text.setPreferredSize(new Dimension(130, text.getPreferredSize().height));
        add(text, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        if (!(field instanceof JLabel)) {
            field.setFont(Comic.regular(13f));
        }
        add(field, c);
    }

    private void updatePreview() {
        String text = name.getText().trim();
        preview.setText(text.isEmpty() ? " " : "→ " + fullName(context, text));
    }

    /** Der volle Name: mit Punkt eingegeben gilt die Eingabe, sonst Kontext + „.“ + Eingabe (MainframeMate). */
    static String fullName(String context, String input) {
        String upper = input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
        if (upper.contains(".")) {
            return upper;
        }
        String base = context == null ? "" : context.trim().toUpperCase(Locale.ROOT)
                .replaceAll("(\\.\\*|\\*|\\.)+$", "");
        return base.isEmpty() ? upper : base + "." + upper;
    }

    /** Liest das Formular; wirft mit lesbarer Meldung, wenn etwas nicht passt. */
    FtpDatasetsView.NewDataset request() {
        String text = name.getText().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Bitte einen Namen eingeben");
        }
        DatasetAttributes attributes = new DatasetAttributes(
                (DatasetAttributes.Organization) organization.getSelectedItem(), (String) recordFormat.getSelectedItem(),
                number(recordLength, 80, "LRECL"), number(blockSize, 27920, "BLKSIZE"), number(primary, 10, "Primär"),
                number(secondary, 5, "Sekundär"), number(directory, 10, "Directory-Blöcke"));
        return new FtpDatasetsView.NewDataset(text, attributes);
    }

    private static int number(PlaceholderField field, int fallback, String what) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " muss eine Zahl sein, nicht „" + text + "“");
        }
    }

    /** Zeigt das Formular als Overlay; {@code null} bei Abbruch. Fehler bleiben im Formular stehen. */
    static FtpDatasetsView.NewDataset ask(Component parent, String context) {
        NewDatasetForm form = new NewDatasetForm(context);
        while (Dialogs.formOverlay(parent, "Neues Mainframe-Dataset anlegen", form, "Anlegen")) {
            try {
                return form.request();
            } catch (IllegalArgumentException e) {
                form.problem.setText(e.getMessage());
            }
        }
        return null;
    }
}
