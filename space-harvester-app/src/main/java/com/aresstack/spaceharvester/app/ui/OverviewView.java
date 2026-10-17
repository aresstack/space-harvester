package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;

/**
 * Übersicht: Was ist eingerichtet, was fehlt, was läuft gerade und was ist zuletzt passiert. Jede Kachel führt in den
 * Bereich, in dem sich die Sache erledigen lässt.
 */
public final class OverviewView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel attention = new JPanel();
    private final JPanel sources = new JPanel(new GridLayout(0, 2, 12, 12));
    private final Facts recent = new Facts();
    private final ComposerButton toActivities = Comic.button("Alle Aktivitäten", "Aufträge ansehen");

    public OverviewView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        attention.setOpaque(false);
        attention.setLayout(new BoxLayout(attention, BoxLayout.Y_AXIS));
        sources.setOpaque(false);
        toActivities.addActionListener(e -> context.navigator().show(AppContext.Section.ACTIVITIES));
        JPanel activity = new JPanel(new BorderLayout(0, 8));
        activity.setOpaque(false);
        activity.add(recent, BorderLayout.CENTER);
        activity.add(Comic.row(toActivities), BorderLayout.SOUTH);
        root.add(Comic.column(
                Comic.plate("Zu erledigen", Comic.RED, attention),
                Comic.plate("Wissensquellen", Comic.BLUE, sources),
                Comic.plate("Zuletzt", Comic.PETROL, activity)), BorderLayout.CENTER);
        context.onChange(ignored -> render());
        render();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        render();
    }

    private void render() {
        attention.removeAll();
        sources.removeAll();
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            note(context.startProblem() == null ? "Die App startet …"
                    : "Der Start ist fehlgeschlagen: " + Problems.describe(context.startProblem()).title()
                    + ". Details unter Einstellungen.", AppContext.Section.SETTINGS, "Zu den Einstellungen");
        } else {
            if (context.settings().sources().isEmpty()) {
                note("Noch keine Wissensquelle eingerichtet. Unter Wissensquellen führt „Quelle hinzufügen …“ durch die "
                        + "Einrichtung.", AppContext.Section.SOURCES, "Zu den Wissensquellen");
            }
            if (!context.settings().problems().isEmpty()) {
                note(Texts.count(context.settings().problems().size(), "Hinweis", "Hinweise")
                        + " zur Konfiguration: " + context.settings().problems().get(0),
                        AppContext.Section.SETTINGS, "Alle ansehen");
            }
            if (!runtime.rejectedSources().isEmpty()) {
                note(Texts.count(runtime.rejectedSources().size(), "Quelle fehlt", "Quellen fehlen")
                        + " wegen fehlerhafter Einstellungen: " + runtime.rejectedSources().get(0),
                        AppContext.Section.SETTINGS, "Alle ansehen");
            }
            for (String id : runtime.waitingForSecret()) {
                note("Für „" + id + "“ fehlen die Zugangsdaten.", AppContext.Section.SOURCES, "Zugangsdaten eingeben");
            }
            for (HarvestSource source : runtime.workspace().sources()) {
                sources.add(tile(source));
            }
        }
        if (attention.getComponentCount() == 0) {
            attention.add(Comic.muted("Nichts offen."));
        }
        renderRecent(runtime);
        root.revalidate();
        root.repaint();
    }

    private void renderRecent(AppRuntime runtime) {
        recent.reset();
        if (runtime == null) {
            return;
        }
        Background.run(() -> runtime.workspace().jobs(), jobs -> {
            recent.reset();
            List<JobStatus> shown = jobs.stream().limit(6).toList();
            if (shown.isEmpty()) {
                recent.add("Aufträge", "noch keine");
            }
            long running = jobs.stream().filter(j -> !j.state().terminal()).count();
            if (running > 0) {
                recent.add("Laufend", String.valueOf(running), Comic.BLUE);
            }
            for (JobStatus job : shown) {
                recent.add(Texts.type(job.type()) + " " + job.sourceId() + " / " + job.partitionKey(),
                        Texts.state(job.state()) + " · " + Texts.time(job.createdAt()),
                        job.state() == JobState.COMPLETED ? Comic.INK : Comic.tone(Texts.tone(job.state())));
            }
            recent.revalidate();
        }, error -> recent.add("Aufträge", Problems.describe(error).title(), Comic.RED));
    }

    private JComponent tile(HarvestSource source) {
        AppContext.Connection connection = context.connection(source.id());
        Facts facts = new Facts();
        facts.add("Art", Texts.sourceType(source.type()));
        switch (connection.state()) {
            case CONNECTED -> facts.add("Verbindung", "geprüft · " + Texts.count(connection.spaces().size(), "Space",
                    "Spaces"), Comic.GREEN);
            case FAILED -> facts.add("Verbindung", Problems.describe(connection.error()).title(), Comic.RED);
            case CHECKING -> facts.add("Verbindung", "wird geprüft …");
            default -> facts.add("Verbindung", "nicht geprüft", Comic.MUTED);
        }
        ComposerButton open = Comic.button("Öffnen", "Quelle unter Wissensquellen ansehen");
        open.addActionListener(e -> context.navigator().show(AppContext.Section.SOURCES));
        ComposerButton check = Comic.button("Prüfen", "Verbindung prüfen");
        check.addActionListener(e -> context.checkConnection(source.id(), () -> { }));
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);
        body.add(facts, BorderLayout.CENTER);
        body.add(Comic.row(open, check), BorderLayout.SOUTH);
        return Comic.plate(source.title() == null || source.title().isBlank() ? source.id() : source.title(),
                Comic.YELLOW, body);
    }

    private void note(String text, AppContext.Section section, String action) {
        ComposerButton go = Comic.button(action, null);
        go.addActionListener(e -> context.navigator().show(section));
        JPanel line = new JPanel(new BorderLayout(10, 0));
        line.setOpaque(false);
        line.add(Comic.text(text), BorderLayout.CENTER);
        line.add(go, BorderLayout.EAST);
        line.setAlignmentX(0f);
        attention.add(line);
    }
}
