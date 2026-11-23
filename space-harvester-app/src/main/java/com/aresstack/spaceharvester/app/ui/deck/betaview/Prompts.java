package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.SaveChooser;
import com.aresstack.spaceharvester.deck.betaview.DownloadChoices;
import com.aresstack.spaceharvester.deck.betaview.DownloadOptions;
import com.aresstack.spaceharvester.deck.betaview.InfoLine;
import com.aresstack.spaceharvester.deck.betaview.NameAndDescription;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JFileChooser;

/**
 * Die Rückfragen des BetaView-Bereichs (Name und Beschreibung, Download-Optionen, Speichern unter, Dokumentinfo). Eine
 * Naht, damit Tests ohne modale Fenster auskommen; die App fragt über {@link #dialogs()} als Overlays im App-Stil.
 */
interface Prompts {

    /** Name und Beschreibung, vorbelegt mit {@code proposal}; leer bei Abbruch. */
    Optional<NameAndDescription> nameAndDescription(Component parent, String title, NameAndDescription proposal,
                                                    String action);

    /** Optionen zum Herunterladen; leer bei Abbruch. */
    Optional<DownloadOptions> download(Component parent, DownloadChoices choices);

    /** Wohin speichern, im Ausgabeordner der App vorgeschlagen; leer bei Abbruch. */
    Optional<Path> saveAs(Component parent, String fileName);

    /** Zeigt Angaben als Liste „Bezeichnung: Wert“. */
    void show(Component parent, String title, List<InfoLine> lines);

    /** Zeigt einen längeren Text (Druckinformationen) zum Lesen. */
    default void showText(Component parent, String title, String text) {
        show(parent, title, List.of(new InfoLine("", text)));
    }

    static Prompts dialogs() {
        return new Prompts() {
            @Override
            public Optional<NameAndDescription> nameAndDescription(Component parent, String title,
                                                                   NameAndDescription proposal, String action) {
                PlaceholderField name = new PlaceholderField(24, "Name");
                PlaceholderField description = new PlaceholderField(24, "Beschreibung (optional)");
                name.setText(proposal.name());
                description.setText(proposal.description());
                JPanel body = form();
                row(body, 0, "Name", name);
                row(body, 1, "Beschreibung", description);
                if (!Dialogs.formOverlay(parent, title, body, action) || name.getText().isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new NameAndDescription(name.getText(), description.getText()));
            }

            @Override
            public Optional<DownloadOptions> download(Component parent, DownloadChoices choices) {
                DownloadForm form = new DownloadForm(choices);
                if (!Dialogs.formOverlay(parent, "Wie herunterladen?", form, "Herunterladen")) {
                    return Optional.empty();
                }
                return Optional.of(form.options());
            }

            @Override
            public Optional<Path> saveAs(Component parent, String fileName) {
                JFileChooser chooser = SaveChooser.create(fileName);
                return chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION
                        ? Optional.of(chooser.getSelectedFile().toPath()) : Optional.empty();
            }

            @Override
            public void show(Component parent, String title, List<InfoLine> lines) {
                JPanel body = form();
                int y = 0;
                for (InfoLine line : lines) {
                    JLabel value = Comic.text(line.value());
                    if (line.label().isEmpty()) {
                        GridBagConstraints c = new GridBagConstraints();
                        c.gridy = y++;
                        c.gridwidth = 2;
                        c.anchor = GridBagConstraints.WEST;
                        c.insets = new Insets(3, 0, 3, 0);
                        body.add(value, c);
                    } else {
                        row(body, y++, line.label(), value);
                    }
                }
                Dialogs.view(parent, title, body);
            }

            @Override
            public void showText(Component parent, String title, String text) {
                javax.swing.JTextArea area = Comic.code();
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setText(text);
                area.setCaretPosition(0);
                JComponent scroll = Comic.scroll(area);
                scroll.setPreferredSize(new Dimension(560, 320));
                Dialogs.view(parent, title, scroll);
            }
        };
    }

    static JPanel form() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        return panel;
    }

    /** Eine Formularzeile: Bezeichnung links mit fester Breite, Feld rechts, bündig wie alle Formulare der App. */
    static void row(JPanel panel, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(3, 0, 3, 10);
        c.anchor = GridBagConstraints.WEST;
        if (field instanceof PatternFields.Field) {
            // Auswahl mit Eingaben darunter: Bezeichnung auf Höhe der Liste, nicht in der Mitte.
            c.anchor = GridBagConstraints.FIRST_LINE_START;
            c.insets = new Insets(8, 0, 3, 10);
        }
        JLabel text = Comic.text(label);
        text.setPreferredSize(new Dimension(118, text.getPreferredSize().height));
        panel.add(text, c);
        c.gridx = 1;
        c.insets = new Insets(3, 0, 3, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        field.setFont(Comic.regular(13f));
        panel.add(field, c);
    }

    /** Das Formular „Herunterladen mit Optionen“ wie in MainframeMate: Seiten, Format, CSV, Notizen. */
    final class DownloadForm extends JPanel {

        final JComboBox<DownloadOptions.Pages> pages = Comic.combo();
        final PlaceholderField from = new PlaceholderField(5, "1");
        final PlaceholderField to;
        final JComboBox<DownloadOptions.Format> format = Comic.combo();
        final JComboBox<DownloadOptions.Delimiter> delimiter = Comic.combo();
        final JComboBox<DownloadOptions.Split> split = Comic.combo();
        final PlaceholderField csvValue = new PlaceholderField(8, "10");
        final JCheckBox trim = Comic.checkBox("Leerzeichen trimmen");
        final JComboBox<DownloadOptions.Notes> notes = Comic.combo();
        final JComboBox<DownloadOptions.NoteKind> noteKind = Comic.combo();
        private final JPanel range = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        private final JPanel csv = form();
        private final int total;

        DownloadForm(DownloadChoices choices) {
            super(new GridBagLayout());
            setOpaque(false);
            total = choices.pages();
            to = new PlaceholderField(5, String.valueOf(choices.pages()));
            for (DownloadOptions.Pages value : DownloadOptions.Pages.values()) {
                pages.addItem(value);
            }
            format.addItem(DownloadOptions.Format.ORIGINAL);
            if (choices.csvEnabled()) {
                format.addItem(DownloadOptions.Format.CSV);
            }
            for (DownloadOptions.Delimiter value : DownloadOptions.Delimiter.values()) {
                delimiter.addItem(value);
            }
            for (DownloadOptions.Split value : DownloadOptions.Split.values()) {
                split.addItem(value);
            }
            for (DownloadOptions.Notes value : DownloadOptions.Notes.values()) {
                notes.addItem(value);
            }
            for (DownloadOptions.NoteKind value : DownloadOptions.NoteKind.values()) {
                noteKind.addItem(value);
            }
            range.setOpaque(false);
            range.add(Comic.text("von"));
            range.add(from);
            range.add(Comic.text("bis"));
            range.add(to);
            row(csv, 0, "Trenner", delimiter);
            row(csv, 1, "Trennstelle", split);
            row(csv, 2, "Wert", csvValue);
            row(csv, 3, "", trim);
            row(this, 0, "Seiten", pages);
            row(this, 1, "", range);
            row(this, 2, "Format", format);
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = 3;
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            add(csv, c);
            row(this, 4, "Notizen", notes);
            row(this, 5, "Welche Notizen", noteKind);
            pages.addActionListener(e -> update());
            format.addActionListener(e -> update());
            update();
        }

        private void update() {
            range.setVisible(pages.getSelectedItem() == DownloadOptions.Pages.RANGE);
            csv.setVisible(format.getSelectedItem() == DownloadOptions.Format.CSV);
            revalidate();
            java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
            if (window != null) {
                window.pack();
            }
        }

        DownloadOptions options() {
            DownloadOptions.Pages chosen = (DownloadOptions.Pages) pages.getSelectedItem();
            int first = number(from.getText(), 1);
            int last = number(to.getText(), total);
            return new DownloadOptions(chosen, first, last, (DownloadOptions.Format) format.getSelectedItem(),
                    (DownloadOptions.Delimiter) delimiter.getSelectedItem(),
                    (DownloadOptions.Split) split.getSelectedItem(),
                    csvValue.getText().isBlank() ? "10" : csvValue.getText(), trim.isSelected(),
                    (DownloadOptions.Notes) notes.getSelectedItem(),
                    (DownloadOptions.NoteKind) noteKind.getSelectedItem());
        }

        private static int number(String text, int fallback) {
            try {
                return text == null || text.isBlank() ? fallback : Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
