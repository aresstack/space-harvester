package com.aresstack.spaceharvester.app.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Set;
import javax.swing.*;
import tools.jackson.databind.json.JsonMapper;

/** Require a fresh explicit source selection before opening the local MCP endpoint. */
final class ProgramMcpPanel extends JPanel {
    private final AppContext context;

    ProgramMcpPanel(AppContext context) {
        super(new BorderLayout(0, 6));
        this.context = context;
        setOpaque(false);
        add(Comic.note("Nur lokal, nur lesend und nur für ausdrücklich ausgewählte KI-freigegebene FTP-/NDV-Quellen. "
                + "Beim Beenden oder Neustart der Laufzeit wird der Zugriff geschlossen."), BorderLayout.CENTER);
        var manage = new JButton("MCP-Zugriff verwalten");
        manage.addActionListener(event -> manage());
        add(Comic.row(manage), BorderLayout.SOUTH);
    }

    private void manage() {
        var runtime = context.runtime();
        if (runtime == null) {
            Dialogs.view(this, "MCP-Zugriff", Comic.note("Keine aktive Laufzeit."));
            return;
        }
        var running = runtime.programMcp();
        if (running.isPresent()) {
            showConnection(runtime, running.get());
            return;
        }
        var allowed = runtime.workspace().programMcpSources().stream()
                .filter(runtime.settings().ai().externalProcessingSources()::contains).sorted().toArray(String[]::new);
        if (allowed.length == 0) {
            Dialogs.view(this, "MCP-Zugriff", Comic.note("Keine KI-freigegebene Programmquelle vorhanden."));
            return;
        }
        var sources = new JList<>(allowed);
        sources.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        var body = new JPanel(new BorderLayout(0, 6));
        body.add(Comic.note("Wähle die Quellen, deren gespeicherte Programmanalysen externe lokale MCP-Clients lesen dürfen. "
                + "Die Auswahl gilt nur bis zum Stoppen des Endpunkts."), BorderLayout.NORTH);
        var scroll = Comic.scroll(sources);
        scroll.setPreferredSize(new Dimension(420, 180));
        body.add(scroll, BorderLayout.CENTER);
        if (!Dialogs.formOverlay(this, "MCP-Quellen freigeben", body, "Endpunkt starten")
                || context.runtime() != runtime) return;
        if (sources.isSelectionEmpty()) {
            Dialogs.view(this, "MCP-Zugriff", Comic.note("Keine Quellen ausgewählt. Endpunkt bleibt ausgeschaltet."));
            return;
        }
        try {
            showConnection(runtime, runtime.startProgramMcp(Set.copyOf(sources.getSelectedValuesList())));
        } catch (java.io.IOException | RuntimeException unavailable) {
            Dialogs.view(this, "MCP-Zugriff", Comic.note("Endpunkt konnte nicht gestartet werden."));
        }
    }

    private void showConnection(com.aresstack.spaceharvester.app.runtime.AppRuntime runtime,
                                com.aresstack.spaceharvester.app.runtime.program.ProgramMcpServer server) {
        var body = new JPanel(new BorderLayout(0, 6));
        body.add(Comic.note("Aktiv auf 127.0.0.1. Streamable HTTP (MCP 2025-11-25). "
                + "Verwende die URL und den Authorization-Header in einem lokalen MCP-Client. "
                + "Das Token gewährt Zugriff auf die ausgewählten Quellen; nicht weitergeben."), BorderLayout.NORTH);
        String config = JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(
                java.util.Map.of("url", server.url(), "headers", java.util.Map.of("Authorization", "Bearer " + server.token())));
        var details = new JTextArea(config, 8, 48);
        details.setEditable(false);
        var scroll = Comic.scroll(details);
        body.add(scroll, BorderLayout.CENTER);
        if (Dialogs.formOverlay(this, "Lokaler MCP-Endpunkt aktiv", body, "Endpunkt stoppen")
                && context.runtime() == runtime && runtime.programMcp().filter(current -> current == server).isPresent()) {
            runtime.stopProgramMcp();
        }
    }
}
