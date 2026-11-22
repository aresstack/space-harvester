package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SaveChooser;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.MvsPath;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.deck.ftp.RemoteFile;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;

/**
 * Unteransicht „Datasets“ des FTP-Bereichs (MVS-Dateibrowser wie in MainframeMate): oben der Pfad mit Zurück, Hoch und
 * Neu laden im Feld und rechts ein Filter, links die Liste (Datasets wie Ordner, Members wie Dateien), rechts die
 * Vorschau des gewählten Members. Die Aktionen stehen unten in der Werkzeugleiste. Bearbeitet wird später im
 * gemeinsamen Editor des Command Deck; hier wird nur gelesen, übertragen, angelegt, gelöscht und eingereicht.
 */
final class FtpDatasetsView extends JPanel {

    private final FtpSessionView owner;
    private final Runnable showJobs;
    private final PlaceholderField path = new PlaceholderField(30, "HLQ oder Dataset, etwa 'HLQ.JCL'");
    private final PlaceholderField filter = new PlaceholderField(12, "Filtern");
    private final Entries model = new Entries();
    private final JTable table = Comic.table(new JTable(model));
    private final JTextArea preview = Comic.code();
    private final JLabel previewTitle = Comic.caption("Vorschau");
    private final PlaceholderField newName = new PlaceholderField(12, "");
    private final JLabel newLabel = Comic.text("");
    private final JPanel newBar = new JPanel(new BorderLayout(8, 0));
    private final JPanel actions;
    private final ComposerButton download;
    private final ComposerButton upload;
    private final ComposerButton newMember;
    private final ComposerButton newDataset;
    private final ComposerButton delete;
    private final ComposerButton submit;
    private final IconButton back = new IconButton(Glyph.BACK, "Zurück (zum vorher gezeigten Pfad)");
    private final Deque<String> history = new ArrayDeque<>();
    private String current = "";
    private boolean creatingDataset;
    private boolean connected;

    FtpDatasetsView(FtpSessionView owner, Runnable showJobs) {
        super(new BorderLayout());
        this.owner = owner;
        this.showJobs = showJobs;
        setOpaque(false);

        // Kopf: Pfad mit seinen Knöpfen im Feld, rechts der Filter.
        InputFrame pathFrame = new InputFrame(path);
        back.addActionListener(e -> back());
        var up = InputFrame.flatButton("↑", "Eine Ebene höher");
        up.addActionListener(e -> up());
        IconButton reload = new IconButton(Glyph.REFRESH, "Neu laden");
        reload.addActionListener(e -> navigate(path.getText(), false));
        pathFrame.addButton(back);
        pathFrame.addButton(up);
        pathFrame.addButton(reload);
        path.addActionListener(e -> navigate(path.getText(), true));
        InputFrame filterFrame = InputFrame.search(filter);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                model.filter(filter.getText());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                model.filter(filter.getText());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                model.filter(filter.getText());
            }
        });
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        head.add(pathFrame, BorderLayout.CENTER);
        head.add(filterFrame, BorderLayout.EAST);

        // „Neu …“ als Zeile über der Liste, mit ✕ (Meldungskästen oben mit ✕).
        newBar.setOpaque(false);
        newBar.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        InputFrame nameFrame = new InputFrame(newName);
        var create = InputFrame.flatButton("Anlegen", "Anlegen (Eingabe)");
        create.addActionListener(e -> create());
        var cancel = InputFrame.flatButton("✕", "Nicht anlegen (Esc)");
        cancel.addActionListener(e -> newBar.setVisible(false));
        nameFrame.addButton(create);
        nameFrame.addButton(cancel);
        newName.addActionListener(e -> create());
        newName.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancel-new");
        newName.getActionMap().put("cancel-new", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                newBar.setVisible(false);
            }
        });
        newBar.add(newLabel, BorderLayout.WEST);
        newBar.add(nameFrame, BorderLayout.CENTER);
        newBar.setVisible(false);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(head, BorderLayout.NORTH);
        north.add(newBar, BorderLayout.SOUTH);

        // Mitte: Liste | Vorschau.
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    open(selected());
                }
            }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-entry");
        table.getActionMap().put("open-entry", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                open(selected());
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                enableActions();
            }
        });
        table.getAccessibleContext().setAccessibleName("Datasets und Members");
        preview.setEditable(false);
        preview.getAccessibleContext().setAccessibleName("Vorschau");
        JPanel previewPanel = new JPanel(new BorderLayout(0, 4));
        previewPanel.setOpaque(false);
        previewPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        previewPanel.add(previewTitle, BorderLayout.NORTH);
        previewPanel.add(Comic.scroll(preview), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Comic.scroll(table), previewPanel);
        ComicDivider.install(split, Comic.PALETTE);
        split.setResizeWeight(0.42);
        split.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        split.setOpaque(false);

        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        download = Comic.button("Herunterladen …", "Gewähltes Member als Textdatei speichern");
        download.addActionListener(e -> download());
        upload = Comic.button("Hochladen …", "Lokale Textdatei hierher hochladen (als Member bzw. Datei)");
        upload.addActionListener(e -> upload());
        newMember = Comic.button("Neues Member …", "Leeres Member in diesem Dataset anlegen");
        newMember.addActionListener(e -> askNew(false));
        newDataset = Comic.button("Neues Dataset …", "Dataset unter diesem Pfad anlegen");
        newDataset.addActionListener(e -> askNew(true));
        delete = Comic.danger("Löschen", "Gewählten Eintrag löschen (mit Rückfrage)");
        delete.addActionListener(e -> delete());
        submit = Comic.button("Als Job einreichen", "Gewähltes Member als JCL an JES übergeben");
        submit.addActionListener(e -> submit());
        actions = Comic.row(download, upload, newMember, newDataset, submit, delete);
        enableActions();
    }

    JPanel actions() {
        return actions;
    }

    void setConnected(boolean value) {
        connected = value;
        enableActions();
    }

    /** Erstes Zeigen nach dem Verbinden: der HLQ des Benutzers. */
    void start() {
        FtpSession session = owner.session();
        owner.run(session.files(files -> files.home()), "Startpfad nicht ermittelt", home -> navigate(home, false));
    }

    /** Listet einen Pfad. */
    void navigate(String target, boolean remember) {
        FtpSession session = owner.session();
        String resolved = session.mvs() ? MvsPath.absolute(target) : (target == null || target.isBlank() ? "/" : target);
        owner.status("Liste " + resolved + " …", false);
        owner.run(session.files(files -> files.list(resolved)), "Liste von " + resolved + " nicht geladen", list -> {
            if (remember && !current.isEmpty() && !current.equals(resolved)) {
                history.push(current);
            }
            current = resolved;
            path.setText(resolved);
            model.sizes(!session.mvs());
            model.show(list);
            preview.setText("");
            previewTitle.setText("Vorschau");
            newBar.setVisible(false);
            owner.status(resolved + " · " + list.size() + (list.size() == 1 ? " Eintrag" : " Einträge"), false);
            enableActions();
        });
    }

    private void back() {
        if (!history.isEmpty()) {
            navigate(history.pop(), false);
        }
    }

    private void up() {
        if (current.isEmpty()) {
            return;
        }
        String parent = owner.session().mvs() ? MvsPath.parent(current) : unixParent(current);
        if (!MvsPath.ROOT.equals(parent)) {
            navigate(parent, true);
        }
    }

    private static String unixParent(String path) {
        int slash = path.endsWith("/") ? path.lastIndexOf('/', path.length() - 2) : path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private void open(RemoteEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.directory()) {
            navigate(entry.path(), true);
            return;
        }
        owner.status("Lese " + entry.path() + " …", false);
        owner.run(owner.session().files(files -> files.read(entry.path())), "Lesen von " + entry.path()
                + " fehlgeschlagen", file -> {
            preview.setText(file.text());
            preview.setCaretPosition(0);
            previewTitle.setText("Vorschau · " + file.path());
            owner.status(file.path() + " · " + lines(file.text()) + " Zeilen", false);
        });
    }

    private static int lines(String text) {
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    private RemoteEntry selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.at(table.convertRowIndexToModel(row));
    }

    private void enableActions() {
        RemoteEntry entry = selected();
        boolean file = entry != null && !entry.directory();
        boolean mvs = owner.session().mvs();
        download.setEnabled(connected && file);
        submit.setEnabled(connected && file);
        delete.setEnabled(connected && entry != null);
        upload.setEnabled(connected && !current.isEmpty());
        newMember.setEnabled(connected && !current.isEmpty() && (!mvs || MvsPath.unquote(current).contains(".")));
        newDataset.setEnabled(connected && !current.isEmpty());
        newMember.setText(mvs ? "Neues Member …" : "Neue Datei …");
        newDataset.setText(mvs ? "Neues Dataset …" : "Neuer Ordner …");
        back.setEnabled(!history.isEmpty());
    }

    private void askNew(boolean dataset) {
        creatingDataset = dataset;
        boolean mvs = owner.session().mvs();
        String where = current;
        newLabel.setText(dataset ? (mvs ? "Neues Dataset unter " + MvsPath.unquote(where) + ".  " : "Neuer Ordner:  ")
                : (mvs ? "Neues Member in " + where + ":  " : "Neue Datei:  "));
        newName.setPlaceholder(dataset ? (mvs ? "nur der neue Teil, etwa NEU" : "Name") : (mvs ? "max. 8 Zeichen" : "Name"));
        newName.setText("");
        newBar.setVisible(true);
        revalidate();
        newName.requestFocusInWindow();
    }

    private void create() {
        String name = newName.getText().trim();
        if (name.isEmpty()) {
            owner.status("Bitte einen Namen eingeben", true);
            return;
        }
        boolean mvs = owner.session().mvs();
        String target;
        if (mvs) {
            name = name.toUpperCase(Locale.ROOT);
            if (!creatingDataset && !MvsPath.isMemberName(name)) {
                owner.status("„" + name + "“ ist kein gültiger Member-Name (1–8 Zeichen, Buchstaben, Ziffern, @ # $)",
                        true);
                return;
            }
            target = creatingDataset ? MvsPath.absolute(MvsPath.unquote(current) + "." + name)
                    : MvsPath.absolute(MvsPath.memberSpec(MvsPath.unquote(current), name));
        } else {
            target = current.endsWith("/") ? current + name : current + "/" + name;
        }
        String created = target;
        boolean dataset = creatingDataset;
        owner.run(owner.session().filesOnce(files -> {
            if (dataset) {
                files.createDirectory(created);
            } else {
                files.write(created, "");
            }
            return created;
        }), "Anlegen von " + created + " fehlgeschlagen", done -> {
            newBar.setVisible(false);
            navigate(current, false);
            owner.status("Angelegt: " + done, false);
        });
    }

    private void delete() {
        RemoteEntry entry = selected();
        if (entry == null || !Dialogs.confirm(this, "Löschen", "„" + entry.path() + "“ wirklich löschen?")) {
            return;
        }
        owner.run(owner.session().filesOnce(files -> {
            files.delete(entry.path());
            return entry.path();
        }), "Löschen von " + entry.path() + " fehlgeschlagen", done -> {
            navigate(current, false);
            owner.status("Gelöscht: " + done, false);
        });
    }

    private void download() {
        RemoteEntry entry = selected();
        if (entry == null || entry.directory()) {
            return;
        }
        JFileChooser chooser = SaveChooser.create(fileName(entry) + ".txt");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        owner.run(owner.session().files(files -> {
            RemoteFile file = files.read(entry.path());
            Files.writeString(target, file.text(), StandardCharsets.UTF_8);
            return target;
        }), "Herunterladen von " + entry.path() + " fehlgeschlagen",
                saved -> owner.status("Gespeichert: " + saved, false));
    }

    private void upload() {
        JFileChooser chooser = new JFileChooser(SaveChooser.directory().toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = chooser.getSelectedFile().toPath();
        uploadFile(source);
    }

    /** Lädt eine lokale Textdatei in den gezeigten Pfad (für Tests direkt). */
    void uploadFile(Path source) {
        boolean mvs = owner.session().mvs();
        String base = source.getFileName().toString();
        int dot = base.indexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String target;
        if (mvs) {
            String member = stem.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9@#$]", "");
            member = member.length() > 8 ? member.substring(0, 8) : member;
            if (!MvsPath.isMemberName(member) || !MvsPath.unquote(current).contains(".")) {
                owner.status("Hochladen geht in ein Dataset (PDS); der Dateiname muss als Member-Name taugen", true);
                return;
            }
            target = MvsPath.absolute(MvsPath.memberSpec(MvsPath.unquote(current), member));
        } else {
            target = current.endsWith("/") ? current + base : current + "/" + base;
        }
        String destination = target;
        owner.run(owner.session().filesOnce(files -> {
            files.write(destination, asText(Files.readString(source, StandardCharsets.UTF_8)));
            return destination;
        }), "Hochladen nach " + destination + " fehlgeschlagen", done -> {
            navigate(current, false);
            owner.status("Hochgeladen: " + done, false);
        });
    }

    /**
     * Text einer lokalen Datei wie im Editor: Windows-Zeilenenden werden {@code \n}, ein letzter Umbruch fällt weg (sonst
     * entstünde auf dem Host ein leerer letzter Satz).
     */
    static String asText(String local) {
        String text = local.replace("\r\n", "\n");
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private void submit() {
        RemoteEntry entry = selected();
        if (entry == null || entry.directory()) {
            return;
        }
        FtpSession session = owner.session();
        owner.status("Reiche " + entry.path() + " ein …", false);
        owner.run(session.files(files -> files.read(entry.path()).text())
                        .thenCompose(jcl -> session.jobsOnce(jes -> jes.submit(jcl))),
                "Einreichen von " + entry.path() + " fehlgeschlagen", job -> {
                    owner.status(FtpJobsView.submitted(job), false);
                    owner.jobs().refreshLater(FtpJobsView.submitted(job));
                    showJobs.run();
                });
    }

    private static String fileName(RemoteEntry entry) {
        String name = MvsPath.isMember(entry.path()) ? MvsPath.splitMember(entry.path())[1] : entry.name();
        return name.replaceAll("[\\\\/:*?\"<>|']", "_");
    }

    /** Für Tests. */
    List<RemoteEntry> shown() {
        return List.copyOf(model.shown);
    }

    String current() {
        return current;
    }

    String previewText() {
        return preview.getText();
    }

    JTable table() {
        return table;
    }

    void openRow(int row) {
        table.getSelectionModel().setSelectionInterval(row, row);
        open(selected());
    }

    /** Die Einträge mit Filter. */
    private final class Entries extends AbstractTableModel {

        private final List<RemoteEntry> all = new ArrayList<>();
        private final List<RemoteEntry> shown = new ArrayList<>();
        private String needle = "";
        /** MVS nennt über NLST keine Größen; dann entfällt die Spalte. */
        private boolean sizes = true;

        void sizes(boolean shown) {
            if (sizes != shown) {
                sizes = shown;
                fireTableStructureChanged();
            }
        }

        void show(List<RemoteEntry> entries) {
            all.clear();
            all.addAll(entries);
            apply();
        }

        void filter(String text) {
            needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            apply();
        }

        private void apply() {
            shown.clear();
            all.stream().filter(entry -> needle.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(needle))
                    .sorted((a, b) -> a.directory() == b.directory() ? a.name().compareToIgnoreCase(b.name())
                            : a.directory() ? -1 : 1)
                    .forEach(shown::add);
            fireTableDataChanged();
        }

        RemoteEntry at(int row) {
            return shown.get(row);
        }

        @Override
        public int getRowCount() {
            return shown.size();
        }

        @Override
        public int getColumnCount() {
            return sizes ? 3 : 2;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Name";
                case 1 -> "Art";
                default -> "Größe";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            RemoteEntry entry = shown.get(row);
            boolean mvs = owner.session().mvs();
            return switch (column) {
                case 0 -> entry.name();
                case 1 -> entry.directory() ? (mvs ? "Dataset" : "Ordner")
                        : (mvs ? (entry.path().contains("(") ? "Member" : "Dataset (sequenziell)") : "Datei");
                default -> entry.size() < 0 ? "" : size(entry.size());
            };
        }

        private static String size(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return String.format(Locale.GERMANY, "%.1f KB", bytes / 1024.0);
            }
            return String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
