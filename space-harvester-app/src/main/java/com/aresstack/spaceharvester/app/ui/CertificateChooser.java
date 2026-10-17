package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Auswahl eines Client-Zertifikats aus dem Windows-Zertifikatsspeicher ({@code Windows-MY}) wie im Enterprise AI
 * Client ({@code CertificateChooser}, ursprünglich aus MainframeMate): Tabelle mit Alias, Inhaber (CN), „Gültig bis“
 * und Status; zuerst nur gültige Zertifikate, mit Häkchen auch abgelaufene; der bisherige Alias ist vorgewählt.
 * Gelesen werden nur die öffentlichen Zertifikate, nie private Schlüssel. Außerhalb von Windows bleibt die Tabelle
 * leer. Nur das Fenster ist das der App (Comic-Stil statt Systemdialog).
 */
final class CertificateChooser {

    /** Ein Zertifikat des Speichers, ohne Schlüssel. */
    record Entry(String alias, String subject, Date validUntil, String sha1, boolean valid) {
    }

    private CertificateChooser() {
    }

    /**
     * Die Zertifikate aus {@code Windows-MY} mit privatem Schlüssel (nur die taugen für mTLS); leer ohne Windows oder
     * bei einem Lesefehler.
     */
    static List<Entry> load() {
        List<Entry> result = new ArrayList<>();
        try {
            KeyStore store = KeyStore.getInstance("Windows-MY");
            store.load(null, null);
            Date now = new Date();
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!store.isKeyEntry(alias)) {
                    continue;
                }
                Certificate certificate = store.getCertificate(alias);
                if (certificate instanceof X509Certificate x509) {
                    String sha1 = HexFormat.ofDelimiter(":").withUpperCase()
                            .formatHex(MessageDigest.getInstance("SHA-1").digest(x509.getEncoded()));
                    result.add(new Entry(alias, x509.getSubjectX500Principal().getName(), x509.getNotAfter(), sha1,
                            now.after(x509.getNotBefore()) && now.before(x509.getNotAfter())));
                }
            }
        } catch (Exception e) {
            // Ohne Windows (oder ohne Zugriff) gibt es nichts zur Auswahl; der Alias lässt sich weiter eintippen.
        }
        return result;
    }

    /** @return der gewählte Alias oder {@code null} bei Abbruch; nur auf dem EDT */
    static String choose(Component parent, String currentAlias) {
        return choose(parent, currentAlias, load());
    }

    static String choose(Component parent, String currentAlias, List<Entry> all) {
        String[] result = {null};
        JDialog dialog = Dialogs.dialog(parent, "Zertifikat auswählen");
        JTable table = Comic.table(new JTable());
        JCheckBox expired = new JCheckBox("Auch abgelaufene Zertifikate anzeigen");
        expired.setOpaque(false);
        expired.setFont(Comic.regular(12.5f));
        List<Entry> shown = new ArrayList<>();
        Runnable fill = () -> {
            shown.clear();
            all.stream().filter(entry -> expired.isSelected() || entry.valid()).forEach(shown::add);
            table.setModel(model(shown));
            table.getColumnModel().getColumn(0).setPreferredWidth(170);
            table.getColumnModel().getColumn(1).setPreferredWidth(320);
            table.getColumnModel().getColumn(2).setPreferredWidth(140);
            table.getColumnModel().getColumn(3).setPreferredWidth(90);
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).alias().equals(currentAlias)) {
                    table.setRowSelectionInterval(i, i);
                }
            }
        };
        expired.addActionListener(e -> fill.run());
        fill.run();
        ComposerButton ok = Comic.primary("Übernehmen", null);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ok.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < shown.size()) {
                result[0] = shown.get(row).alias();
            }
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        Dialogs.show(dialog, "Zertifikat auswählen", body(table, expired, all.isEmpty()), cancel, ok);
        return result[0];
    }

    static JComponent body(JTable table, JCheckBox expired, boolean empty) {
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(Comic.text(empty
                ? "Im Windows-Zertifikatsspeicher (CurrentUser\\My) wurde kein Zertifikat gefunden."
                : "Zertifikat aus dem Windows-Zertifikatsspeicher (CurrentUser\\My) auswählen:"), BorderLayout.NORTH);
        JComponent scroll = Comic.scroll(table);
        scroll.setPreferredSize(new Dimension(740, 280));
        body.add(scroll, BorderLayout.CENTER);
        body.add(expired, BorderLayout.SOUTH);
        return body;
    }

    private static DefaultTableModel model(List<Entry> entries) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY);
        DefaultTableModel model = new DefaultTableModel(new String[] {"Alias", "Inhaber", "Gültig bis", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (Entry entry : entries) {
            model.addRow(new Object[] {entry.alias(), commonName(entry.subject()), format.format(entry.validUntil()),
                    entry.valid() ? "Gültig" : "Abgelaufen"});
        }
        return model;
    }

    /** Der CN aus dem Inhaber, sonst der gekürzte Name. */
    static String commonName(String subject) {
        if (subject == null) {
            return "";
        }
        for (String part : subject.split(",")) {
            String trimmed = part.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("CN=")) {
                return trimmed.substring(3).trim();
            }
        }
        return subject.length() > 60 ? subject.substring(0, 60) + "…" : subject;
    }
}
