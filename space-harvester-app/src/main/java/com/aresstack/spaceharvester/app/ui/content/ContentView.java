package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.SignInSlot;
import com.aresstack.spaceharvester.app.ui.View;
import com.aresstack.spaceharvester.application.passages.PassageId;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;

/**
 * Inhalte: die Leseansicht. Welche Seite sie zeigt, entscheidet der Explorer links (oder die Suche, ein Auftrag, eine
 * Adresse); eine eigene Quellen-, Space- oder Seitenauswahl hat sie nicht. Oben steht, wo die Seite liegt
 * (Wissensquelle › Space), daneben öffnet „Seiten-ID oder Adresse“ eine Seite der aktuellen Wissensquelle direkt.
 */
public final class ContentView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel crumbs = new JLabel(" ");
    private final PlaceholderField openId = new PlaceholderField(20, "Seiten-ID oder Adresse …");
    private final PageDetailPanel detail;
    private final JPanel body = new JPanel(new BorderLayout());
    /** Die Einmalcode-Abfrage der gezeigten Wissensquelle (ADR 0018), etwa für Anhänge und Bilder. */
    private final SignInSlot signIn;

    private String sourceId;
    private String spaceKey;

    public ContentView(AppContext context) {
        this.context = context;
        this.detail = new PageDetailPanel(context);
        detail.onShown(this::pageShown);
        this.signIn = new SignInSlot(context, requests -> sourceId == null ? java.util.Optional.empty()
                : requests.pendingFor(sourceId));
        // Nach dem Einmalcode lädt die gezeigte Seite neu, damit Bilder und Anhänge ohne Sperre erscheinen.
        signIn.onAccepted(detail::reloadShown);
        root.setBackground(Comic.SURFACE);

        crumbs.setFont(Comic.regular(13f));
        crumbs.setForeground(Comic.MUTED);
        openId.setToolTipText("Eine Seite der aktuellen Wissensquelle direkt öffnen: Seiten-ID oder Adresse mit "
                + "pageId, dann Enter");
        openId.addActionListener(e -> openTyped());

        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setBackground(Comic.SURFACE);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        top.add(crumbs, BorderLayout.CENTER);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        tools.setOpaque(false);
        tools.add(openId);
        top.add(tools, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(top, BorderLayout.NORTH);
        signIn.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
        north.add(signIn, BorderLayout.CENTER);
        root.add(north, BorderLayout.NORTH);
        body.setOpaque(false);
        root.add(body, BorderLayout.CENTER);
        body.add(detail, BorderLayout.CENTER);
        context.onChange(ignored -> renderCrumbs());
        renderCrumbs();
    }

    @Override
    public JComponent component() {
        return root;
    }

    /** Ein Space wurde gewählt (z. B. „Inhalte öffnen“ eines Auftrags): ohne Seite, die wählt man im Explorer. */
    public void openSpace(String sourceId, String spaceKey) {
        detail.clear();
        this.sourceId = sourceId;
        this.spaceKey = spaceKey;
        renderCrumbs();
    }

    /** Öffnet eine Seite (aus dem Explorer, der Suche, einem Auftrag). */
    public void openPage(String sourceId, String pageId) {
        if (!Objects.equals(sourceId, this.sourceId)) {
            this.spaceKey = null;
        }
        this.sourceId = sourceId;
        renderCrumbs();
        detail.load(sourceId, pageId, false);
    }

    /**
     * Öffnet einen Absatz über seine stabile Passage-ID (#452): seine Seite im Reiter „Absätze“, der Absatz markiert.
     * {@code sourceId} ist die Quelle des Aufrufers (z. B. eines Suchtreffers); maßgeblich ist die ID selbst.
     */
    public void openPassage(String sourceId, String passageId) {
        String source = PassageId.looksLike(passageId) ? PassageId.parse(passageId).sourceId() : sourceId;
        if (!Objects.equals(source, this.sourceId)) {
            this.spaceKey = null;
        }
        this.sourceId = source;
        renderCrumbs();
        detail.openPassage(passageId);
    }

    /** Nach dem Laden kennt die Seite ihren Space. */
    private void pageShown(String sourceId, String spaceKey) {
        this.sourceId = sourceId;
        this.spaceKey = spaceKey == null || spaceKey.isBlank() ? null : spaceKey;
        renderCrumbs();
    }

    private void openTyped() {
        String text = openId.getText().trim();
        String source = currentSource();
        if (source == null || text.isEmpty()) {
            return;
        }
        if (text.contains("://")) {
            detail.loadByUrl(source, text);
        } else {
            openPage(source, text);
        }
    }

    /** Die Wissensquelle der gezeigten Seite; ohne Seite die einzige, wenn es nur eine gibt. */
    private String currentSource() {
        if (sourceId != null) {
            return sourceId;
        }
        List<HarvestSource> sources = sources();
        return sources.size() == 1 ? sources.get(0).id() : null;
    }

    private void renderCrumbs() {
        signIn.refresh();
        String source = currentSource();
        if (source == null) {
            crumbs.setText(sources().isEmpty() ? "Keine Wissensquelle" : "Seite im Explorer wählen");
            openId.setEnabled(false);
            return;
        }
        openId.setEnabled(true);
        crumbs.setText(spaceKey == null ? title(source) : title(source) + " › " + spaceKey);
    }

    private String title(String id) {
        for (HarvestSource source : sources()) {
            if (source.id().equals(id)) {
                return source.title() == null || source.title().isBlank() ? id : source.title();
            }
        }
        return id;
    }

    private List<HarvestSource> sources() {
        AppRuntime runtime = context.runtime();
        return runtime == null ? List.of() : runtime.workspace().sources();
    }

    String chosenSource() {
        return sourceId;
    }

    String chosenSpace() {
        return spaceKey;
    }

    String crumbs() {
        return crumbs.getText();
    }

    PlaceholderField openId() {
        return openId;
    }

    PageDetailPanel detail() {
        return detail;
    }
}
