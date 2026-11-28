package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.runtime.FollowUpJobs;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Relation;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.images.PageImages;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ende zu Ende ohne Bildschirm (#115): zwei Fake-Confluences, Abgleich über die echte Laufzeit der App, dann zeigt der
 * Reiter „Inhalt“ die Akronyme aus der Core-Analyse {@code acronyms}: jede Fundstelle markiert, bekannte und unbekannte
 * Ausschreibungen, getrennt nach Quelle, nichts auf einer neueren Live-Fassung, „Analyse neu berechnen“ aktualisiert.
 */
class ReaderAcronymsTest {

    private static final String WIKI_PAGE = "<h1>Schnittstellen</h1><p>Die Application Programming Interface (API) "
            + "nutzt REST.</p><p>Jede API und alle APIs melden sich per SSO (Single Sign-On) an. RAPID und xAPI "
            + "zählen nicht als API-Treffer. Grüße an die ÜBV.</p>";
    private static final String OTHER_PAGE = "<h1>Prüfung</h1><p>Die API (Automatische Prüfungs Instanz) prüft "
            + "jede Lieferung.</p>";

    private static void layoutAll(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    @TempDir
    Path home;

    private final List<HttpServer> servers = new ArrayList<>();
    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void theReaderMarksTheCoreAcronymsOfTheShownPageOnly() throws Exception {
        FakeConfluence wiki = confluence("200", "Schnittstellen", WIKI_PAGE);
        FakeConfluence other = confluence("200", "Prüfung", OTHER_PAGE);
        Workspace workspace = start(serve(wiki), serve(other));
        sync(workspace, "wiki");
        sync(workspace, "zweites");

        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));
        ReaderDocument shown = open(panel, "wiki", "200", false);

        assertThat(shown.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(shown.acronyms().result().processorId()).isEqualTo("acronyms");
        assertThat(shown.acronyms().result().processorVersion()).isEqualTo("4;sentences=opennlp-de");
        assertThat(shown.acronyms().result().key().sourceId()).isEqualTo("wiki");
        assertThat(onEdt(panel::acronymHint)).contains("Core-Analyse „acronyms“ Version 4")
                .contains("Fundstellen markiert");
        String text = onEdt(() -> panel.reader().getDocument().getText(0, panel.reader().getDocument().getLength()));
        assertThat(text).isEqualTo(shown.text().text());
        // Dokumentnavigation des Core (#396) als Wolkenbanner über dem Lesetext; ohne Modell nur die Überschriften.
        Navigation navigation = onEdt(() -> panel.navigationSky().navigation());
        assertThat(navigation).isNotNull();
        assertThat(navigation.analysis()).isEqualTo(Navigation.Analysis.HEADINGS);
        assertThat(navigation.chapters()).extracting(Navigation.Section::label).containsExactly("Schnittstellen");
        Navigation.Section chapter = navigation.chapters().get(0);
        assertThat(text.substring(chapter.start())).startsWith("Schnittstellen");

        List<String> marked = shown.marks().stream()
                .map(mark -> text.substring(mark.occurrence().start(), mark.occurrence().end())).toList();
        assertThat(marked).containsExactly("API", "REST", "API", "APIs", "SSO", "RAPID", "xAPI", "API", "ÜBV");
        assertThat(acronym(shown, "API").count()).isEqualTo(4);
        assertThat(acronym(shown, "API").expansion()).isEqualTo("Application Programming Interface");
        assertThat(acronym(shown, "SSO").expansion()).isEqualTo("Single Sign-On");
        assertThat(ReaderDocument.tooltip(acronym(shown, "REST")))
                .isEqualTo("REST: Ausschreibung unbekannt (steht nicht auf dieser Seite)");
        assertThat(ReaderDocument.tooltip(acronym(shown, "API"))).doesNotStartWith("<html>");

        StyledDocument document = shown.document();
        for (PageAcronyms.Mark mark : shown.marks()) {
            for (int offset = mark.occurrence().start(); offset < mark.occurrence().end(); offset++) {
                assertThat(StyleConstants.isUnderline(document.getCharacterElement(offset).getAttributes()))
                        .as("markiert bei %d", offset).isTrue();
            }
        }
        // Gemischte Schreibung wie im EAC: xAPI ist ein eigenes Akronym, kein API-Treffer.
        int xApi = text.indexOf("xAPI") + 1;
        assertThat(shown.markAt(xApi).acronym().acronym()).isEqualTo("xAPI");
        int heading = text.indexOf("Schnittstellen");
        assertThat(StyleConstants.isBold(document.getCharacterElement(heading).getAttributes())).isTrue();

        // Tastatur: Alt+→ springt zur ersten Fundstelle und zeigt die Ausschreibung
        onEdt(() -> {
            panel.reader().setCaretPosition(0);
            panel.jumpToAcronym(true);
            return null;
        });
        assertThat(onEdt(() -> panel.reader().getSelectedText())).isEqualTo("API");
        assertThat(onEdt(panel::acronymDetail)).isEqualTo("API: Application Programming Interface · 4× auf dieser "
                + "Seite · zuerst in „Schnittstellen“");
        onEdt(() -> {
            panel.reader().setCaretPosition(text.indexOf("REST") + 1);
            return null;
        });
        assertThat(onEdt(panel::acronymDetail)).startsWith("REST: Ausschreibung unbekannt");

        // Reiter „Begriffe“ (#339): dieselben Akronyme; ein Klick springt zur nächsten Fundstelle im Lesetext.
        assertThat(onEdt(() -> panel.terms().stateLabel())).isEqualTo("Aktuell");
        assertThat(onEdt(() -> panel.terms().counts().get(0)))
                .isEqualTo(String.valueOf(shown.acronyms().acronyms().size()));
        onEdt(() -> {
            panel.reader().setCaretPosition(text.indexOf("SSO") + 3);
            panel.jumpTo(acronym(shown, "API"));
            return null;
        });
        assertThat(onEdt(() -> panel.reader().getSelectionStart())).isGreaterThan(text.indexOf("SSO"));
        assertThat(onEdt(() -> panel.reader().getSelectedText())).isEqualTo("API");
        // Bezeichner aus dem Begriffsindex des Core (#119), für genau diesen Text
        assertThat(shown.terms().state()).isEqualTo(PageTerms.State.CURRENT);
        assertThat(onEdt(() -> panel.terms().counts().get(1)))
                .isEqualTo(String.valueOf(shown.terms().identifierCount()));
        assertThat(onEdt(() -> panel.terms().identifierChips().size())).isEqualTo(shown.terms().identifierCount());
        // Das globale Verzeichnis (Bereich „Begriffe“) liest denselben Index.
        TermQueries.TermList api = workspace.terms(new TermQueries.TermSearch(null, null, TermKind.ACRONYM, "API",
                true, 10, null));
        assertThat(api.terms()).singleElement().satisfies(term -> {
            assertThat(term.expansions()).contains("Application Programming Interface");
        });

        // Absätze (#354): ohne Embedding-Modell die strukturelle Rückfallebene des Core, Klick markiert den Absatz
        assertThat(shown.passages().state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(onEdt(() -> panel.passages().titles())).hasSize(shown.passages().passages().size()).isNotEmpty();
        assertThat(onEdt(() -> panel.passages().mode())).isEqualTo(PassagesTab.STRUCTURAL);
        PagePassages.Passage first = shown.passages().passages().get(0);
        onEdt(() -> {
            panel.reader().setCaretPosition(0);
            panel.passages().click(0);
            return null;
        });
        assertThat(onEdt(() -> panel.reader().getSelectionStart())).isEqualTo(first.start());
        assertThat(onEdt(() -> panel.reader().getSelectionEnd())).isEqualTo(first.end());

        // Reiter „Bilder“ (#362): ohne Vision-Modell nennt er den Grund des Core und bietet kein Beschreiben an.
        onEdt(() -> {
            panel.selectTab("Bilder");
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.images().hint()).equals(ImagesTab.NO_MODEL));
        assertThat(onEdt(() -> panel.images().describeOffered())).isFalse();
        assertThat(onEdt(() -> panel.images().stateLabel())).isEqualTo("Nicht durchgeführt");
        assertThat(context.runtime().workspace().images(context.runtime().workspace().page("wiki", "200", false))
                .reason()).isEqualTo(PageImages.VISION_MODEL_MISSING);
        onEdt(() -> {
            panel.tabs().select("Podcast-Ansicht");
            return null;
        });

        // Beziehungen (#120): Vorschläge der Akronym-Regel stehen im Reiter „Begriffe“ genau dieser Seite.
        assertThat(workspace.proposeAcronymRelations("wiki").added()).isPositive();
        ReaderDocument related = open(panel, "wiki", "200", false);
        assertThat(related.relations()).extracting(Relation::line)
                .contains("API · Abkürzung für · Application Programming Interface (Vorgeschlagen, Regel)");
        assertThat(onEdt(() -> panel.terms().counts().get(2))).isEqualTo(String.valueOf(related.relations().size()));

        // Gleiche Seiten-ID, gleiches Akronym, andere Quelle: eigene Ausschreibung, nichts aus „wiki“
        ReaderDocument foreign = open(panel, "zweites", "200", false);
        assertThat(foreign.acronyms().result().key().sourceId()).isEqualTo("zweites");
        assertThat(foreign.acronyms().acronyms()).extracting(PageAcronyms.Acronym::acronym).containsExactly("API");
        assertThat(acronym(foreign, "API").expansion()).isEqualTo("Automatische Prüfungs Instanz");
        assertThat(onEdt(panel::acronymDetail)).isEqualTo(" ");
        assertThat(acronym(open(panel, "wiki", "200", false), "API").expansion())
                .isEqualTo("Application Programming Interface");

        // Neuberechnung ersetzt den Lesetext durch einen neu gebauten mit denselben Fundstellen
        ReaderDocument before = onEdt(panel::readerDocument);
        onEdt(() -> {
            panel.recompute();
            return null;
        });
        ReaderDocument recomputed = await(panel, next -> next != null && next != before);
        assertThat(recomputed.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(recomputed.marks()).hasSameSizeAs(before.marks());

        // Neue Fassung in der Quelle: die Prüfung legt sie im Cache ab und analysiert sie sofort (ADR 0010)
        wiki.page("200", "Schnittstellen", "DEV", "<p>neu</p>")
                .storage("<p>Jetzt nur noch CI (Continuous Integration) und eine API.</p>")
                .version(4, "2026-10-17T10:00:00.000+02:00");
        ReaderDocument live = open(panel, "wiki", "200", true);
        assertThat(live.text().text()).contains("Continuous Integration");
        assertThat(live.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(live.acronyms().acronyms()).extracting(PageAcronyms.Acronym::acronym)
                .containsExactly("API", "CI");

        // Nach dem nächsten Abgleich gehört die Analyse zur neuen Fassung
        sync(workspace, "wiki");
        ReaderDocument synced = open(panel, "wiki", "200", false);
        assertThat(synced.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(synced.acronyms().acronyms()).extracting(PageAcronyms.Acronym::acronym)
                .containsExactly("API", "CI");
        assertThat(acronym(synced, "CI").expansion()).isEqualTo("Continuous Integration");
    }

    /** BG2a: die Begriffe einer Quelle aus dem Core, Listen und Gesamtzahlen über dieselbe Seitenmenge. */
    @Test
    void theTermsOfASourceComeFromTheCoreWithTheirRealTotals() throws Exception {
        Workspace workspace = start(serve(confluence("200", "Schnittstellen", WIKI_PAGE)),
                serve(confluence("200", "Prüfung", OTHER_PAGE)));
        sync(workspace, "wiki");
        sync(workspace, "zweites");

        SelectionTerms wiki = new SelectionTerms(context.runtime(), new ExportPanel.Selection.Node("wiki", null, null,
                "Wiki"));
        // startSync gleicht mit SYNC_CACHE ab: neue Seiten sind nur katalogisiert; erst das Lesen lädt und analysiert.
        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));
        open(panel, "wiki", "200", false);
        open(panel, "zweites", "200", false);
        TermsTab.Totals totals = wiki.totals();
        List<TermQueries.TermSummary> acronyms = wiki.acronyms("").next(TermQueries.MAX_LIMIT);
        assertThat(acronyms).extracting(TermQueries.TermSummary::term).contains("API", "SSO");
        assertThat(acronyms).extracting(TermQueries.TermSummary::normalized).isSorted();
        assertThat(totals.acronyms()).as("Gesamtzahl wie die Liste").isEqualTo(acronyms.size());
        assertThat(totals.identifiers()).isEqualTo(wiki.identifiers("").next(TermQueries.MAX_LIMIT).size())
                .isPositive();
        assertThat(totals.relations()).isEqualTo(wiki.relations("").next(TermQueries.MAX_LIMIT).size());
        // Die Fundstellen eines Begriffs bleiben in der Auswahl: API nur auf der Seite der Quelle „wiki“.
        TermQueries.TermSummary api = acronyms.stream().filter(term -> term.term().equals("API")).findFirst()
                .orElseThrow();
        assertThat(wiki.occurrences(api).occurrences()).extracting(TermQueries.Occurrence::sourceId)
                .containsOnly("wiki");
        // „Wissensquellen“: keine Seitenmenge, beide Quellen zählen.
        SelectionTerms all = new SelectionTerms(context.runtime(), ExportPanel.Selection.ALL);
        assertThat(all.pages()).isNull();
        assertThat(all.occurrences(api).occurrences()).extracting(TermQueries.Occurrence::sourceId)
                .contains("wiki", "zweites");
    }

    /**
     * Fahrplan P2 über die echte Laufzeit: „mit Unterseiten“ ist an einer Seite mit Unterseite frei, Standard aus;
     * angehakt zeigt „Begriffe“ unter den eigenen die der Unterseite, dynamisch aus dem Core, und ein Begriff beider
     * Seiten steht einmal oben mit „+1“. An der Unterseite selbst (ohne Unterseiten) bleibt die Checkbox umschaltbar
     * (Angelo 23.11.2026: nie ausgegraut), der Tooltip sagt, dass es keine Unterseiten gibt.
     */
    @Test
    void withSubpagesTheTermsTabShowsTheTermsOfTheSubpagesBelowItsOwn() throws Exception {
        FakeConfluence wiki = new FakeConfluence();
        wiki.space("DEV", "Entwicklung");
        FakeConfluence.FakePage head = wiki.page("200", "Schnittstellen", "DEV", "<p>Ansicht</p>").storage(WIKI_PAGE);
        wiki.homepage("DEV", "200");
        wiki.page("210", "Zahlungen", "DEV", "<p>Ansicht</p>").storage("<p>Der ZV (Zahlungsverkehr) nutzt die "
                + "API.</p>");
        wiki.child("200", "210");
        Workspace workspace = start(serve(wiki), serve(confluence("300", "Leer", "<p>leer</p>")));
        sync(workspace, "wiki");

        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));
        open(panel, "wiki", "210", false);
        awaitTrue(() -> onEdt(() -> panel.subpages().known() != null));
        assertThat(onEdt(() -> panel.subpages().box().isEnabled())).as("Blatt ohne Unterseiten").isTrue();
        assertThat(onEdt(() -> panel.subpages().box().getToolTipText())).isEqualTo(Subpages.TIP_NO_CHILDREN);
        // An- und wieder abhaken geht auch am Blatt; es gibt nur nichts darunter.
        onEdt(() -> {
            panel.subpages().box().setSelected(true);
            return null;
        });
        assertThat(onEdt(() -> panel.subpages().checkedWithoutChildren())).isTrue();
        assertThat(onEdt(() -> panel.subpages().below())).isNull();
        assertThat(onEdt(() -> panel.subpages().box().isEnabled())).isTrue();
        onEdt(() -> {
            panel.subpages().box().setSelected(false);
            return null;
        });

        open(panel, "wiki", "200", false);
        awaitTrue(() -> onEdt(() -> panel.subpages().known() != null && panel.subpages().known().size() == 1));
        assertThat(onEdt(() -> panel.subpages().box().isEnabled())).isTrue();
        assertThat(onEdt(() -> panel.subpages().checked())).as("Standard aus").isFalse();
        assertThat(onEdt(() -> panel.terms().showsBelow())).isFalse();

        onEdt(() -> {
            panel.subpages().box().setSelected(true);
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.terms().belowChips().stream()
                .anyMatch(chip -> chip.getText().startsWith("ZV"))));
        assertThat(onEdt(() -> panel.terms().belowChips())).extracting(chip -> chip.getText())
                .noneMatch(text -> text.startsWith("API"));
        assertThat(onEdt(() -> panel.terms().alsoBelowTexts())).contains("+1");

        // P3 Beziehungen: die der Unterseite („ZV · Zahlungsverkehr“) abgetrennt, Herkunft die Unterseite; die von
        // Hand angelegte Beziehung zu „API“ betrifft die Seite selbst und steht nur oben.
        workspace.addRelation(new com.aresstack.spaceharvester.application.terms.TermRelations.NewRelation("ZV",
                "Zahlungsverkehr", com.aresstack.spaceharvester.application.terms.RelationType.ABBREVIATION,
                com.aresstack.spaceharvester.application.terms.RelationScope.GLOBAL, null, List.of()));
        workspace.addRelation(new com.aresstack.spaceharvester.application.terms.TermRelations.NewRelation("API",
                "Schnittstelle", com.aresstack.spaceharvester.application.terms.RelationType.RELATED,
                com.aresstack.spaceharvester.application.terms.RelationScope.GLOBAL, null, List.of()));
        List<SubpageSection.Row> relations = PageDetailPanel.belowRelations(workspace, "wiki", "200",
                onEdt(() -> panel.subpages().below()));
        assertThat(relations).anySatisfy(row -> {
            assertThat(row.cells().get(0) + " " + row.cells().get(2)).contains("ZV");
            assertThat(row.originText()).isEqualTo("Zahlungen");
        });
        assertThat(relations).noneSatisfy(row -> assertThat(row.cells().get(0)).isEqualTo("API"));
        onEdt(() -> {
            panel.selectTab("Beziehungen");
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.analysisView().below().count() > 0));
        assertThat(onEdt(() -> panel.analysisView().below().table().getColumnName(4))).isEqualTo("Unterseite");
        // Die sieben Reiter stehen mittig in der Fußleiste der Seitenleiste, „mit Unterseiten“ als Zeile darüber; über
        // dem Inhalt gibt es keine Reiterzeile. Bei 900 px alle in einer Zeile.
        assertThat(onEdt(() -> {
            javax.swing.JPanel foot = panel.analysisFoot();
            foot.setSize(900, foot.getPreferredSize().height);
            layoutAll(foot);
            java.util.Set<Integer> rows = new java.util.HashSet<>();
            for (String name : panel.analysisRow().tabs().names()) {
                javax.swing.AbstractButton tab = panel.analysisRow().tabs().button(name);
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(tab, foot)).isTrue();
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(tab, panel)).isFalse();
                assertThat(tab.isVisible()).as(name).isTrue();
                rows.add(javax.swing.SwingUtilities.convertPoint(tab.getParent(), tab.getLocation(), foot).y);
            }
            javax.swing.JCheckBox box = panel.subpages().box();
            assertThat(javax.swing.SwingUtilities.isDescendingFrom(box, panel.analysesFoot())).isTrue();
            return rows.size() + "/" + panel.analysisTabs().names().size();
        })).isEqualTo("1/7");
        // Zu schmal für alle: Die Leiste bleibt so hoch wie jede Fußleiste, was keinen Platz hat, steht hinter dem
        // Menü am Ende der Reihe; der gewählte Reiter bleibt zu sehen.
        assertThat(onEdt(() -> {
            javax.swing.JPanel foot = panel.analysisFoot();
            foot.setSize(220, foot.getPreferredSize().height);
            layoutAll(foot);
            com.aresstack.spaceharvester.app.ui.controls.OverflowRow bar =
                    (com.aresstack.spaceharvester.app.ui.controls.OverflowRow) panel.analysisRow().bar();
            assertThat(foot.getPreferredSize().height).isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.FOOT_HEIGHT);
            assertThat(bar.hiddenParts()).isNotEmpty();
            assertThat(bar.hiddenParts()).doesNotContain(
                    panel.analysisRow().tabs().button(panel.analysisRow().tabs().selected()));
            for (String name : panel.analysisRow().tabs().names()) {
                javax.swing.AbstractButton tab = panel.analysisRow().tabs().button(name);
                if (!bar.hiddenParts().contains(tab)) {
                    int right = javax.swing.SwingUtilities.convertPoint(tab.getParent(), tab.getLocation(), foot).x
                            + tab.getWidth();
                    assertThat(right).as(name).isLessThanOrEqualTo(foot.getWidth());
                }
            }
            return true;
        })).isTrue();
        assertThat(onEdt(() -> panel.analysisView().createIcon().getAccessibleContext().getAccessibleName()))
                .isEqualTo("Neue Beziehung …");
        assertThat(onEdt(() -> panel.analysisView().proposeIcon().getToolTipText()))
                .isEqualTo("Synonyme vorschlagen");

        // P3c: Bilder, Aussagen und Graph zeigen nur Vorhandenes; die Unterseite ist nicht analysiert, also steht dort
        // nichts, und die Statuszeile sagt, für wie viele Unterseiten noch nichts vorliegt. Kein KI-Lauf. Der Graph
        // rechnet über Seite und Unterseiten mit den Filtern des Reiters (W4); der Abschnitt zählt aus demselben
        // Ergebnis, nur die Unterseiten.
        Subpages.Below subpagesBelow = onEdt(() -> panel.subpages().below());
        com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood.Stack stack =
                workspace.knowledgeGraphOfPages(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki",
                        "200"), subpagesBelow.pages(), onEdt(() -> panel.graph().options()));
        assertThat(stack.missing()).as("Seite und Unterseite ohne Analyse").hasSize(2);
        PageDetailPanel.BelowRows graphRows = PageDetailPanel.belowGraphRows(workspace, subpagesBelow, stack);
        assertThat(graphRows.rows()).isEmpty();
        assertThat(graphRows.missing()).isEqualTo(1);
        assertThat(PageDetailPanel.belowStatus("Graph", 1, graphRows))
                .isEqualTo("für 1 Unterseite liegt noch nichts vor");
        PageDetailPanel.BelowRows statementRows = PageDetailPanel.belowRows("Aussagen", workspace, subpagesBelow,
                () -> true, partial -> { });
        assertThat(statementRows.rows()).isEmpty();
        assertThat(statementRows.missing()).isEqualTo(1);
        assertThat(PageDetailPanel.belowRows("Bilder", workspace, subpagesBelow, () -> true, partial -> { }).rows())
                .isEmpty();
        // Angelo 23.11.2026: „mit Unterseiten“ gilt auch unter „Absätze“; gezeigt wird nur ein vorliegendes Ergebnis.
        PageDetailPanel.BelowRows passageRows = PageDetailPanel.belowRows("Absätze", workspace, subpagesBelow,
                () -> true, partial -> { });
        assertThat(passageRows.missing()).isZero();
        assertThat(passageRows.rows()).singleElement().satisfies(row -> {
            assertThat(row.cells().get(0)).startsWith("Der ZV (Zahlungsverkehr)");
            assertThat(row.originText()).isEqualTo("Zahlungen");
        });
        onEdt(() -> {
            panel.selectTab("Graph");
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.belowSectionOf("Graph").shown()
                && panel.belowSectionOf("Graph").count() == 0));

        // W4: Der Seitengraph selbst rechnet über Seite und Unterseite (ohne Bildschirm das Laden von Hand anstoßen).
        onEdt(() -> {
            panel.graph().showPageNow();
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.graph().subpageStack() != null));
        com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood.Stack graphStack =
                onEdt(() -> panel.graph().subpageStack());
        assertThat(graphStack.considered()).isEqualTo(1);
        // Der Wechsel über „Absätze“ und zurück: Ansicht, Historie und Ergebnis des Graphen bleiben, nichts lädt neu.
        onEdt(() -> {
            panel.selectTab("Absätze");
            panel.selectTab("Graph");
            return null;
        });
        assertThat(onEdt(() -> panel.graph().subpageStack())).isSameAs(graphStack);
        assertThat(onEdt(() -> panel.graph().pageLoaded())).isTrue();

        // „Seite neu laden“ zeigt dieselbe Seite noch einmal, ohne dass die Checkbox etwas Neues meldet: Der Graph
        // rechnet weiter mit den Unterseiten.
        synchronized (wiki) {
            head.version(2, "2026-10-19T08:00:00.000Z");
        }
        open(panel, "wiki", "200", false);
        awaitTrue(() -> !onEdt(panel::updateOffer).isEmpty());
        awaitTrue(() -> onEdt(() -> panel.subpages().below() != null));
        onEdt(() -> {
            panel.clickUpdate();
            panel.graph().showPageNow();
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.graph().subpageStack() != null));
        assertThat(onEdt(() -> panel.graph().subpageStack().considered())).isEqualTo(1);
        assertThat(onEdt(() -> panel.graph().statusText())).contains("mit 1 Unterseite");

        onEdt(() -> {
            panel.subpages().box().setSelected(false);
            return null;
        });
        assertThat(onEdt(() -> panel.terms().showsBelow())).isFalse();
        assertThat(onEdt(() -> panel.terms().alsoBelowTexts())).isEmpty();
    }

    @Test
    void aPageMissingFromTheCacheIsLoadedStoredAndAnalysed() throws Exception {
        FakeConfluence wiki = confluence("200", "Schnittstellen", WIKI_PAGE);
        Workspace workspace = start(serve(wiki), serve(confluence("300", "Leer", "<p>leer</p>")));
        sync(workspace, "wiki");
        wiki.page("201", "Neu", "DEV", "<p>neu</p>").storage("<p>Die API (Application Programming Interface).</p>");

        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));
        ReaderDocument shown = open(panel, "wiki", "201", true);

        assertThat(shown.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(shown.acronyms().acronyms()).extracting(PageAcronyms.Acronym::acronym).containsExactly("API");
        assertThat(shown.marks()).isNotEmpty();
    }

    private static PageAcronyms.Acronym acronym(ReaderDocument document, String name) {
        return document.acronyms().acronyms().stream().filter(a -> a.acronym().equals(name)).findFirst()
                .orElseThrow();
    }

    /**
     * Öffnet die Seite und wartet auf ihre fertige Akronym-Analyse: Nach einem Abgleich läuft die Anreicherung als
     * Folgeauftrag; bis sie fertig ist, zeigt die Seite noch keine Fundstellen. Dann wird die Seite erneut geöffnet.
     */
    private ReaderDocument open(PageDetailPanel panel, String sourceId, String pageId, boolean refresh)
            throws Exception {
        ReaderDocument shown = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            onEdt(() -> {
                panel.load(sourceId, pageId, refresh);
                return null;
            });
            shown = await(panel, document -> document != null);
            if (shown.acronyms().state() == PageAcronyms.State.CURRENT
                    && shown.acronyms().result().key().sourceId().equals(sourceId)) {
                return shown;
            }
            Thread.sleep(250);
        }
        return shown;
    }

    private static ReaderDocument await(PageDetailPanel panel, Predicate<ReaderDocument> ready) throws Exception {
        for (int i = 0; i < 600; i++) {
            ReaderDocument document = onEdt(panel::readerDocument);
            if (ready.test(document)) {
                return document;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Lesetext nicht angezeigt");
    }

    private void sync(Workspace workspace, String sourceId) throws Exception {
        String job = workspace.startSync(sourceId, "DEV").id();
        for (int i = 0; i < 600 && !workspace.job(job).state().terminal(); i++) {
            Thread.sleep(50);
        }
        assertThat(workspace.job(job).state()).isEqualTo(JobState.COMPLETED);
        // Akronyme rechnet die Anreicherung zuerst, Begriffe zuletzt: auf den ganzen Folgeauftrag warten.
        FollowUpJobs.awaitEnrichments(workspace);
    }

    private static FakeConfluence confluence(String pageId, String title, String storage) {
        FakeConfluence confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        confluence.page(pageId, title, "DEV", "<p>Ansicht</p>").storage(storage);
        confluence.homepage("DEV", pageId);
        return confluence;
    }

    private Workspace start(URI wiki, URI other) throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Wiki
                      base-url: %s
                    - id: zweites
                      type: confluence
                      title: Zweites Wiki
                      base-url: %s
                """.formatted(home.resolve("data"), wiki, other));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime()).isNotNull();
        return context.runtime().workspace();
    }

    /** Liefert eine {@link FakeConfluence} über HTTP auf 127.0.0.1 aus. */
    private URI serve(FakeConfluence confluence) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> answer(confluence, exchange));
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + FakeConfluence.BASE.getPath());
    }

    private static void answer(FakeConfluence confluence, HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        ConfluenceHttpResponse response;
        synchronized (confluence) {
            response = confluence.get(mapped, headers, Integer.MAX_VALUE);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void awaitTrue(ThrowingSupplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.get()) {
            assertThat(System.nanoTime()).as("Zeit abgelaufen").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static <T> T onEdt(ThrowingSupplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.get());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
