package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceException;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Die Modellwahl einer Einstellzeile als Untermenü am Dreieck (Angelo 24.11.2026), nach demselben Muster wie
 * {@link ParallelChoice#menu}: „Standard“ (leer in den Einstellungen, wie bisher), darunter die Modelle des KI-Dienstes,
 * der geltende Wert mit Haken, zuletzt „Benutzerdefiniert …“ für ein eingetipptes Modell. Die Wahl schreibt der
 * Aufrufer direkt in die bestehende Einstellung; die Einstellungen selbst ändern sich nicht.
 */
public final class ModelChoice {

    /** Leer in den Einstellungen: das Modell, das ohne eigene Angabe gilt. */
    public static final String STANDARD = "Standard";
    static final String LOADING = "Modelle werden geladen …";

    private ModelChoice() {
    }

    /**
     * Die Modelle des KI-Dienstes, einmal je Ansicht geteilt: das Menü zeigt sofort die zuletzt geladenen und fragt
     * beim Öffnen im Hintergrund neu.
     */
    public static final class Catalog {
        private final Callable<List<AiModel>> source;
        private final List<Runnable> waiting = new ArrayList<>();
        private volatile List<AiModel> models;
        private volatile String failure;
        private boolean loading;

        public Catalog(Callable<List<AiModel>> source) {
            this.source = source;
        }

        /** Die zuletzt geladenen Modelle oder {@code null}, solange noch keine Antwort kam. */
        public List<AiModel> models() {
            return models;
        }

        /** Warum die letzte Abfrage scheiterte, oder {@code null}. */
        public String failure() {
            return failure;
        }

        /** Fragt neu; {@code then} läuft danach auf dem EDT. Läuft schon eine Abfrage, wartet {@code then} auf sie. */
        public void load(Runnable then) {
            waiting.add(then);
            if (loading) {
                return;
            }
            loading = true;
            Background.run(source, loaded -> done(List.copyOf(loaded), null), error -> done(models,
                    error instanceof AiServiceException ai ? AiSettingsPanel.reason(ai) : "Dienst nicht erreichbar"));
        }

        private void done(List<AiModel> loaded, String reason) {
            models = loaded;
            failure = reason;
            loading = false;
            List<Runnable> ready = new ArrayList<>(waiting);
            waiting.clear();
            ready.forEach(Runnable::run);
        }
    }

    /**
     * @param title    die Zeile der Einstellungen, etwa „Podcast“; der geltende Wert steht dahinter
     * @param tip      was das Modell tut
     * @param category die passende Modellart ({@link AiModel#CHAT}, {@link AiModel#TTS}) für die Auswahl
     * @param current  das eingestellte Modell oder {@code null} für „Standard“
     * @param apply    bekommt das neue Modell, {@code null} für „Standard“; nur bei einer Änderung
     */
    public static JMenu menu(Component owner, String title, String tip, String category, String current,
                             Catalog catalog, Consumer<String> apply) {
        JMenu menu = new JMenu(title + ": " + (current == null ? STANDARD : current));
        menu.setFont(Comic.regular(13f));
        menu.setToolTipText(tip);
        Consumer<String> choose = chosen -> {
            String model = chosen == null || chosen.isBlank() ? null : chosen.strip();
            if (!java.util.Objects.equals(model, current)) {
                apply.accept(model);
            }
        };
        fill(menu, owner, title, category, current, catalog, choose);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                catalog.load(() -> {
                    fill(menu, owner, title, category, current, catalog, choose);
                    if (menu.isPopupMenuVisible()) {
                        menu.getPopupMenu().pack();
                    }
                });
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        return menu;
    }

    private static void fill(JMenu menu, Component owner, String title, String category, String current,
                             Catalog catalog, Consumer<String> choose) {
        menu.removeAll();
        JCheckBoxMenuItem standard = item(STANDARD, current == null, () -> choose.accept(null));
        standard.setToolTipText("Wie ohne Eintrag in den Einstellungen: das Modell, das dort leer gilt");
        menu.add(standard);
        menu.addSeparator();
        List<AiModel> models = catalog.models();
        boolean listed = false;
        if (models == null || models.isEmpty()) {
            String note = catalog.failure() != null ? "Modelle nicht abrufbar: " + catalog.failure()
                    : models == null ? LOADING : "Keine Modelle gemeldet";
            JMenuItem empty = new JMenuItem(note);
            empty.setFont(Comic.regular(13f));
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (AiModel model : AiSettingsPanel.choices(models, category)) {
                boolean chosen = model.id().equals(current);
                listed |= chosen;
                JCheckBoxMenuItem item = item(model.displayName().equals(model.id()) ? model.id()
                        : model.displayName() + " (" + model.id() + ")", chosen, () -> choose.accept(model.id()));
                menu.add(item);
            }
        }
        menu.addSeparator();
        boolean own = current != null && !listed;
        menu.add(item(own ? ParallelChoice.CUSTOM + " (" + current + ")" : ParallelChoice.CUSTOM, own, () -> {
            String typed = Dialogs.askText(owner, "Benutzerdefiniert", "Modell für „" + title + "“, wie der "
                    + "KI-Dienst es nennt", current);
            if (typed != null) {
                choose.accept(typed);
            }
        }));
    }

    private static JCheckBoxMenuItem item(String text, boolean selected, Runnable action) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, selected);
        item.setFont(Comic.regular(13f));
        item.addActionListener(e -> action.run());
        return item;
    }
}
