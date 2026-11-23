package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

/**
 * Die Checkbox „mit Unterseiten“ der Analyse-Reiter einer Seite (Fahrplan P2/P3, Angelo 21.11.2026): Standard aus,
 * eine Stellung für alle Analyse-Reiter, gemerkt nur für die Sitzung. Sie ist nie gesperrt (Angelo 23.11.2026: „Der
 * Sinn der Sache war ja, dass ich das selber auswählen kann“): Hat die Seite keine Unterseiten, bleibt sie umschaltbar,
 * die Reiter zeigen nur die Seite, Tooltip und Statuszeile sagen warum. Sie gilt in allen Analyse-Reitern, auch in
 * „Absätze“.
 *
 * <p>Die Nachfahren der gezeigten Seite fragt sie beim Core ab ({@code Workspace.descendants}), nie kopiert; ändert sich
 * der Bestand des Space, fragt sie neu (Beobachter, ADR 0027, kein Zeitgeber). Die Analyse-Reiter melden sich mit
 * {@link #onChange} an und bekommen {@link #below()}: die Nachfahren, wenn angehakt und verfügbar, sonst {@code null}.
 * Nur auf dem EDT.
 */
final class Subpages {

    static final String LABEL = "mit Unterseiten";
    static final String TIP_ON = "Zeigt unter den Einträgen dieser Seite abgetrennt die ihrer Unterseiten";
    static final String TIP_NO_CHILDREN = "Diese Seite hat keine Unterseiten";
    static final String TIP_PENDING = "Die Unterseiten werden noch ermittelt";

    /** Führt Arbeit im Hintergrund aus; in Tests sofort. */
    interface Runner {
        <T> void run(java.util.concurrent.Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    /** Die Nachfahren einer Seite. */
    record Below(String sourceId, String spaceKey, String pageId, PageSelection pages) {

        Below {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(pages, "pages");
        }

        int size() {
            return pages.size();
        }
    }

    private final JCheckBox box = checkBox();
    private final List<Runnable> listeners = new ArrayList<>();
    private Runner runner = com.aresstack.spaceharvester.app.ui.Background::run;
    private Workspace workspace;
    private String sourceId;
    private String spaceKey;
    private String pageId;
    private Below below;
    private int request;
    private boolean resolving;
    private boolean again;
    private Subscription watch;
    private Below announced;
    /** Ein Beobachter meldete geänderten Bestand: Die Reiter laden neu, auch wenn die Seitenmenge gleich blieb. */
    private boolean refresh;
    /** Zählt die Ergebnisse der Abfrage der Nachfahren; siehe {@link #revision()}. */
    private int revision;

    Subpages() {
        box.setSelected(false);
        box.addItemListener(e -> changed());
        describe();
    }

    /**
     * Die Checkbox „mit Unterseiten“, wie sie jede Ebene zeigt: an der Seite hier, am Favoriten-Ordner im Fuß der
     * breiten Ebene (ADR 0034 §6). Nie gesperrt; was sie bedeutet, bindet der Aufrufer.
     */
    static JCheckBox checkBox() {
        JCheckBox box = Comic.checkBox(LABEL);
        box.setFocusable(true);
        return box;
    }

    /** Die Checkbox für die Reiterzeile. */
    JCheckBox box() {
        return box;
    }

    /** Für Tests: Hintergrundarbeit sofort ausführen. */
    void runner(Runner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** {@code listener} läuft, wenn sich {@link #below()} ändert. */
    void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Die gezeigte Seite; {@code workspace} oder {@code pageId} {@code null}: keine Seite. Die Stellung der Checkbox
     * bleibt, die Nachfahren werden neu ermittelt.
     */
    void page(Workspace workspace, String sourceId, String spaceKey, String pageId) {
        boolean same = workspace == this.workspace && Objects.equals(sourceId, this.sourceId)
                && Objects.equals(spaceKey, this.spaceKey) && Objects.equals(pageId, this.pageId);
        if (same) {
            return;
        }
        if (watch != null) {
            watch.close();
            watch = null;
        }
        this.workspace = workspace;
        this.sourceId = sourceId;
        this.spaceKey = spaceKey;
        this.pageId = pageId;
        below = null;
        request++;
        resolving = false;
        again = false;
        refresh = false;
        if (workspace != null && sourceId != null && spaceKey != null && pageId != null) {
            watch(workspace, sourceId, spaceKey);
            resolve();
        }
        changed();
    }

    /** Die Nachfahren der gezeigten Seite, wenn angehakt und es welche gibt; sonst {@code null}. */
    Below below() {
        return box.isSelected() && below != null && below.size() > 0 ? below : null;
    }

    /** Ob „mit Unterseiten“ angehakt ist und die Seite Unterseiten hat (für „Analysieren“ im Reiter Aussagen). */
    boolean active() {
        return below() != null;
    }

    /** Ob die Checkbox angehakt ist. */
    boolean checked() {
        return box.isSelected();
    }

    /** Ob angehakt ist, die Seite aber (fertig ermittelt) keine Unterseiten hat: für die Statuszeile. */
    boolean checkedWithoutChildren() {
        return box.isSelected() && below != null && below.size() == 0;
    }

    /** Die ermittelten Nachfahren, unabhängig von der Checkbox. */
    Below known() {
        return below;
    }

    /**
     * Zählt jedes neue Ergebnis der Abfrage der Nachfahren, auch bei gleicher Seitenmenge (dann hat sich der Bestand
     * geändert). So unterscheidet ein Reiter eine solche Meldung von einer, die nur das Umschalten der Checkbox auslöst.
     */
    int revision() {
        return revision;
    }

    private void watch(Workspace workspace, String sourceId, String spaceKey) {
        try {
            watch = workspace.subscribeHoldings(sourceId, spaceKey, new HoldingsListener() {
                @Override
                public void pagesChanged(String source, String space, Set<String> pageIds) {
                    SwingUtilities.invokeLater(Subpages.this::holdingsChanged);
                }

                @Override
                public void spaceChanged(String source, String space) {
                    SwingUtilities.invokeLater(Subpages.this::holdingsChanged);
                }
            });
        } catch (RuntimeException gone) {
            // Ohne Anmeldung bleibt es beim einmal ermittelten Stand.
        }
    }

    private void holdingsChanged() {
        if (workspace != null && pageId != null) {
            // Auch bei gleicher Seitenmenge können sich Inhalte geändert haben: danach die Reiter neu laden.
            refresh = true;
            resolve();
        }
    }

    /** Fragt die Nachfahren ab; läuft schon eine Abfrage, folgt genau eine weitere danach. */
    private void resolve() {
        if (resolving) {
            again = true;
            return;
        }
        resolving = true;
        again = false;
        int mine = request;
        Workspace from = workspace;
        String source = sourceId;
        String space = spaceKey;
        String page = pageId;
        runner.run(() -> from.descendants(source, space, page), found -> {
            if (mine != request) {
                return;
            }
            resolving = false;
            below = new Below(source, space, page, found);
            revision++;
            boolean reload = refresh && !again;
            if (reload) {
                refresh = false;
                announced = null;
            }
            changed();
            if (again) {
                resolve();
            }
        }, failure -> {
            if (mine != request) {
                return;
            }
            resolving = false;
            below = null;
            changed();
        });
    }

    private void changed() {
        // Nie gesperrt: Der Benutzer stellt selbst ein, ob er die Unterseiten sehen will (Angelo 23.11.2026).
        box.setEnabled(true);
        describe();
        Below now = below();
        if (!Objects.equals(now, announced)) {
            announced = now;
            new ArrayList<>(listeners).forEach(Runnable::run);
        }
    }

    private void describe() {
        String tip;
        if (below == null && pageId != null && resolving) {
            tip = TIP_PENDING;
        } else if (below == null || below.size() == 0) {
            tip = TIP_NO_CHILDREN;
        } else {
            tip = TIP_ON + " (" + below.size() + (below.size() == 1 ? " Unterseite)" : " Unterseiten)");
        }
        box.setToolTipText(tip);
    }
}
