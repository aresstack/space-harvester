package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.SourceFieldSpec;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Die Felder genau einer Quelle, gebaut aus den {@link SourceFieldSpec Feldern} ihres Typs, wie {@code SourceEditor}
 * im Enterprise AI Client: der Editor kennt keinen Typ, er zeigt, was der Typ beschreibt (Text, Zahl, Schalter,
 * Verzeichnis mit Auswahl, Zugangsverweis, Client-Zertifikat mit „Zertifikat verwenden“). Pflichtangaben stehen oben
 * mit Erklärung, alles Übrige unter „Erweitert“ (eingeklappt). Schlüssel unter {@code options}, die der Typ nicht als
 * Feld zeigt (Timeouts …), bleiben beim Speichern unverändert.
 *
 * <p>Die Zeilen kommen in das {@link SourceForm.Form Formular} des Quellenformulars, damit Beschriftungen und Hinweise
 * genauso aussehen wie die übrigen: die Hauptfelder in einen Bereich unter „Art“, „Erweitert“ in einen Bereich unter
 * der technischen Kennung. Ohne KeePass: ein Zugangsverweis ist ein freies Textfeld.
 */
final class SourceFieldsEditor {

    /** Markiert ein Häkchen, dessen Schlüssel in der Datei fehlte. */
    private static final String UNSET_FLAG = "space-harvester.unsetFlag";
    static final String ADVANCED_CLOSED = "▸ Erweitert";
    static final String ADVANCED_OPEN = "▾ Erweitert";
    /** Höchstzahl Zeilen je Bereich (ein Verzeichnis oder Zertifikat braucht eine Zeile, der Schalter eine). */
    static final int ROWS = 40;

    /** Ein Problem an einem Feld. */
    record Problem(JComponent field, String message) {
    }

    private final SourceForm.Form form;
    private final int mainStart;
    private final int advancedStart;
    private final Runnable addressChanged;
    private final Map<String, JComponent> inputs = new LinkedHashMap<>();
    private final Map<String, SourceFieldSpec> specs = new LinkedHashMap<>();
    /** Alle Zeilen (Schlüsselkomponente je Zeile), zum Abbauen beim Typwechsel. */
    private final List<JComponent> rows = new ArrayList<>();
    private final List<JComponent> advancedRows = new ArrayList<>();
    private final ComposerButton advancedToggle = Comic.button(ADVANCED_CLOSED,
            "Weitere Angaben; leer lassen genügt in der Regel");
    private boolean expanded;
    private SourceFieldSpec certificateField;
    private JCheckBox certificateUse;
    private JTextField certificateAlias;
    private ComposerButton certificateChoose;
    private String addressKey;

    /**
     * @param mainStart      reservierter Bereich für die Hauptfelder ({@link SourceForm.Form#reserve})
     * @param advancedStart  reservierter Bereich für „Erweitert“
     * @param addressChanged läuft bei jeder Änderung des Adressfelds (Vorschlag für Name und Kennung)
     */
    SourceFieldsEditor(SourceForm.Form form, int mainStart, int advancedStart, Runnable addressChanged) {
        this.form = form;
        this.mainStart = mainStart;
        this.advancedStart = advancedStart;
        this.addressChanged = addressChanged;
        advancedToggle.addActionListener(e -> advanced(!expanded));
    }

    /**
     * Baut die Felder für einen Typ und füllt sie.
     *
     * @param fields     die Felder des Typs; leer baut nichts
     * @param values     die {@code options} aus der Datei
     * @param fresh      neue Quelle: leere Felder bekommen die Vorgabe des Typs
     * @param addressKey Schlüssel der Adresse ({@code SourceType.addressOption}) oder {@code null}
     */
    void load(List<SourceFieldSpec> fields, Map<String, String> values, boolean fresh, String addressKey) {
        clear();
        this.addressKey = addressKey;
        if (fields.isEmpty()) {
            return;
        }
        List<SourceFieldSpec> main = new ArrayList<>();
        List<SourceFieldSpec> advanced = new ArrayList<>();
        for (SourceFieldSpec field : fields) {
            (field.advanced() ? advanced : main).add(field);
        }
        form.build(mainStart, ROWS, () -> {
            for (SourceFieldSpec field : main) {
                if (field.kind() == SourceFieldSpec.Kind.CLIENT_CERTIFICATE) {
                    certificate(field, values, fresh);
                } else {
                    put(field, input(field, value(field, values, fresh), false));
                }
            }
        });
        form.build(advancedStart, ROWS, () -> {
            JPanel toggle = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            toggle.setOpaque(false);
            toggle.add(advancedToggle);
            form.row(" ", toggle);
            rows.add(advancedToggle);
            for (SourceFieldSpec field : advanced) {
                if (field.kind() == SourceFieldSpec.Kind.CLIENT_CERTIFICATE) {
                    certificate(field, values, fresh);
                } else {
                    put(field, input(field, value(field, values, fresh), true));
                }
            }
        });
        form.visible(advancedToggle, !advanced.isEmpty());
        expanded = false;
        showAdvanced();
        JComponent address = addressKey == null ? null : inputs.get(addressKey);
        if (address instanceof JTextField text) {
            text.getDocument().addDocumentListener(SourceForm.changes(addressChanged));
        }
    }

    /** Nimmt alle Zeilen wieder aus dem Formular. */
    void clear() {
        rows.forEach(form::removeRow);
        rows.clear();
        advancedRows.clear();
        inputs.clear();
        specs.clear();
        certificateField = null;
        certificateUse = null;
        certificateAlias = null;
        certificateChoose = null;
        addressKey = null;
    }

    /** Ob der Typ Felder hat (sonst gilt das Adressfeld des Formulars). */
    boolean active() {
        return !specs.isEmpty();
    }

    private static String value(SourceFieldSpec field, Map<String, String> values, boolean fresh) {
        String value = values.get(field.key());
        return value == null && fresh && !field.defaultValue().isEmpty() ? field.defaultValue() : value;
    }

    private void put(SourceFieldSpec field, JComponent input) {
        inputs.put(field.key(), input);
        specs.put(field.key(), field);
    }

    private JComponent input(SourceFieldSpec field, String value, boolean advanced) {
        String label = field.label() + (field.required() ? " *" : "");
        String hint = field.hint().isEmpty() ? null : field.hint();
        switch (field.kind()) {
            case FLAG -> {
                JCheckBox box = checkBox(field.label());
                String flag = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
                box.setSelected(flag.equals("true") || flag.equals("yes") || flag.equals("ja") || flag.equals("on")
                        || flag.equals("1"));
                if (value == null || value.isBlank()) {
                    // nicht gesetzt: unverändert bleibt der Schlüssel weg, damit die Vorgabe des Typs gilt
                    box.putClientProperty(UNSET_FLAG, Boolean.TRUE);
                }
                add(" ", box, hint, advanced);
                return box;
            }
            case DIRECTORY -> {
                JTextField text = Comic.field(28);
                text.setText(value);
                ComposerButton choose = Comic.button("Verzeichnis wählen …", null);
                choose.addActionListener(e -> chooseDirectory(text));
                JPanel line = new JPanel(new BorderLayout(6, 0));
                line.setOpaque(false);
                line.add(text, BorderLayout.CENTER);
                line.add(choose, BorderLayout.EAST);
                add(label, line, hint, advanced);
                return text;
            }
            default -> {
                JTextField text = Comic.field(28);
                text.setText(value);
                add(label, text, hint, advanced);
                return text;
            }
        }
    }

    /** Eine Zeile im Formular; {@code key} ist das Feld selbst, bei einer Zeile mit Knopf dessen Textfeld. */
    private void add(String label, JComponent field, String hint, boolean advanced) {
        form.row(label, field, hint);
        JComponent key = field instanceof JPanel panel && panel.getComponentCount() > 0
                && panel.getComponent(0) instanceof JComponent inner ? inner : field;
        rows.add(key);
        if (advanced) {
            advancedRows.add(key);
        }
    }

    /**
     * Zertifikat wie im Enterprise AI Client: Häkchen „Zertifikat verwenden“ (Vorgabe an), darunter das
     * schreibgeschützte Feld mit dem Alias und „Zertifikat …“, das die Auswahl aus dem Windows-Zertifikatsspeicher
     * öffnet.
     */
    private void certificate(SourceFieldSpec field, Map<String, String> values, boolean fresh) {
        certificateField = field;
        String use = values.get(field.certificateUseKey());
        certificateUse = checkBox("Zertifikat verwenden");
        certificateUse.setSelected(!"false".equalsIgnoreCase(use == null ? "" : use.trim()));
        add(" ", certificateUse, "mTLS-Zertifikat für die Verbindung verwenden", field.advanced());
        certificateAlias = Comic.field(25);
        certificateAlias.setText(value(field, values, fresh));
        certificateAlias.setEditable(false);
        certificateChoose = Comic.button("Zertifikat …", field.hint().isEmpty() ? null : field.hint());
        certificateChoose.addActionListener(e -> {
            String chosen = CertificateChooser.choose(form, certificateAlias.getText().trim());
            if (chosen != null) {
                certificateAlias.setText(chosen);
            }
        });
        JPanel line = new JPanel(new BorderLayout(6, 0));
        line.setOpaque(false);
        line.add(certificateAlias, BorderLayout.CENTER);
        line.add(certificateChoose, BorderLayout.EAST);
        add(field.label(), line, null, field.advanced());
        specs.put(field.key(), field);
        certificateUse.addActionListener(e -> enableCertificate());
        enableCertificate();
    }

    private void enableCertificate() {
        boolean use = certificateUse.isSelected();
        certificateAlias.setEnabled(use);
        certificateChoose.setEnabled(use);
    }

    /** {@code true}, wenn „Zertifikat verwenden“ an ist, aber kein Zertifikat gewählt wurde (Rückfrage beim Speichern). */
    boolean certificateMissing() {
        return certificateUse != null && certificateUse.isSelected() && certificateAlias.getText().isBlank();
    }

    private static JCheckBox checkBox(String text) {
        JCheckBox box = new JCheckBox(text);
        box.setOpaque(false);
        box.setFont(Comic.regular(13f));
        box.setForeground(Comic.INK);
        return box;
    }

    /** „Erweitert“ auf- oder zuklappen. */
    void advanced(boolean open) {
        expanded = open;
        showAdvanced();
    }

    boolean advancedOpen() {
        return expanded;
    }

    private void showAdvanced() {
        for (JComponent row : advancedRows) {
            form.visible(row, expanded);
        }
        advancedToggle.setText(expanded ? ADVANCED_OPEN : ADVANCED_CLOSED);
        form.revalidate();
        form.repaint();
    }

    /** Klappt „Erweitert“ auf, wenn {@code field} darunter steht (etwa für einen Hinweis). */
    void reveal(JComponent field) {
        if (advancedRows.contains(field) && !expanded) {
            advanced(true);
        }
    }

    /**
     * Die {@code options} mit den Feldern des Typs: geladene Werte, überschrieben mit den Eingaben; ein leeres Feld
     * entfernt seinen Schlüssel, übrige Schlüssel bleiben.
     */
    Map<String, String> apply(Map<String, String> loaded) {
        Map<String, String> options = new TreeMap<>(loaded);
        for (Map.Entry<String, JComponent> entry : inputs.entrySet()) {
            JComponent input = entry.getValue();
            if (input instanceof JCheckBox box) {
                if (Boolean.TRUE.equals(box.getClientProperty(UNSET_FLAG)) && !box.isSelected()) {
                    continue;
                }
                options.put(entry.getKey(), box.isSelected() ? "true" : "false");
            } else {
                String value = ((JTextField) input).getText().strip();
                if (value.isEmpty()) {
                    options.remove(entry.getKey());
                } else {
                    options.put(entry.getKey(), value);
                }
            }
        }
        if (certificateField != null) {
            String alias = certificateAlias.getText().strip();
            if (certificateUse.isSelected() && !alias.isEmpty()) {
                options.put(certificateField.key(), alias);
            } else {
                options.remove(certificateField.key());
            }
            if (certificateUse.isSelected()) {
                options.remove(certificateField.certificateUseKey());
            } else {
                options.put(certificateField.certificateUseKey(), "false");
            }
        }
        return options;
    }

    /** Pflichtangaben und Zahlen; jedes Problem gehört zu seinem Feld. */
    List<Problem> problems() {
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<String, JComponent> entry : inputs.entrySet()) {
            SourceFieldSpec field = specs.get(entry.getKey());
            if (!(entry.getValue() instanceof JTextField text)) {
                continue;
            }
            String value = text.getText().strip();
            if (field.required() && value.isEmpty()) {
                problems.add(new Problem(text, "Bitte „" + field.label() + "“ eintragen."));
            } else if (field.kind() == SourceFieldSpec.Kind.NUMBER && !value.isEmpty() && !value.matches("-?\\d+")) {
                problems.add(new Problem(text, "„" + field.label() + "“ muss eine ganze Zahl sein."));
            }
        }
        return problems;
    }

    /** Die Eingabe zum Schlüssel oder {@code null}. */
    JComponent input(String key) {
        return inputs.get(key);
    }

    /** Das Feld zum Schlüssel oder {@code null}. */
    SourceFieldSpec spec(String key) {
        return specs.get(key);
    }

    /** Die Eingabe des Adressfelds oder {@code null}. */
    JTextField address() {
        return addressKey != null && inputs.get(addressKey) instanceof JTextField text ? text : null;
    }

    /** Der Zugangsverweis ({@link SourceFieldSpec.Kind#SECRET_REF}) oder {@code null}. */
    JTextField secretReference() {
        for (Map.Entry<String, SourceFieldSpec> entry : specs.entrySet()) {
            if (entry.getValue().kind() == SourceFieldSpec.Kind.SECRET_REF
                    && inputs.get(entry.getKey()) instanceof JTextField text) {
                return text;
            }
        }
        return null;
    }

    ComposerButton advancedToggle() {
        return advancedToggle;
    }

    JCheckBox certificateUse() {
        return certificateUse;
    }

    JTextField certificateAlias() {
        return certificateAlias;
    }

    private void chooseDirectory(JTextField target) {
        String current = target.getText().trim();
        JFileChooser chooser = new JFileChooser(current.isEmpty() ? null : current);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Verzeichnis mit Dokumenten wählen");
        if (chooser.showOpenDialog(form) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }
}
