package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.astu.acropolis.attachment.AttachmentException;
import com.aresstack.enterpriseai.astu.acropolis.attachment.AttachmentTextExtractor;
import com.aresstack.enterpriseai.astu.approval.ApprovalPromptPort;
import com.aresstack.enterpriseai.astu.approval.ApprovalRules;
import com.aresstack.enterpriseai.astu.approval.ToolApprovalGate;
import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.ChatBrowserPort;
import com.aresstack.enterpriseai.browser.runtime.BrowserProfileStore;
import com.aresstack.enterpriseai.browser.runtime.ChatBrowserRuntime;
import com.aresstack.enterpriseai.proasteion.application.agent.BrowserMcpTools;
import com.aresstack.enterpriseai.proasteion.application.browser.BrowserAccessService;
import com.aresstack.enterpriseai.proasteion.application.browser.DelegatingBrowserAccessGuard;
import com.aresstack.enterpriseai.proasteion.application.browser.WebExplorationProgress;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolContribution;
import com.aresstack.askai.browser.playwright.PlaywrightChatEngine;
import com.aresstack.spaceharvester.app.ui.AppTimeouts;
import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntimeConfig;
import com.aresstack.spaceharvester.application.assets.DocumentText;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.research.runtime.search.SearchBrowserWebSearch;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der Browser der Chats für die Websuche, zusammengesetzt wie {@code AdapterAssembly.browsers} und die
 * Browser-Teile von {@code CompositionRoot} im Enterprise AI Client, seit ADR 0046 aus Research4j: die Werkzeuge aus
 * {@code browser-tools}, als Engine ein sichtbarer Browser über Playwright ({@link PlaywrightChatEngine}) mit eigenem
 * Profil je Chat unter {@code <Datenordner>/browser-profiles}. Den Browser startet die Browser-Laufzeit
 * ({@code browser-runtime}) über den Adapter {@link RuntimeBrowserLauncher}. Welcher Browser (Firefox als Vorgabe, Edge, Chrome, Camoufox),
 * steht unter Einstellungen › Browser; ist er nicht installiert, gibt es keine Websuche ({@link #start} liefert
 * {@code null}, {@link #unavailable} sagt warum). Jede Adresse prüft die Richtlinie des aktiven Profils (Extern oder Intern);
 * unbekannte Websites und verändernde Aktionen fragt der Chat nach. Nach jeder Antwort schließt der Browser, das
 * Profil bleibt. Gesucht wird im eigenen Suchbrowser ({@link SearchBrowserWebSearch}) mit den Suchmaschinen des
 * Profils; das Chat-Fenster öffnet nur die Treffer.
 */
public final class ChatWebBrowser {

    static final String PROFILES_DIRECTORY = "browser-profiles";
    private static final System.Logger LOG = System.getLogger(ChatWebBrowser.class.getName());

    private final ChatBrowserPort browsers;
    private final BrowserMcpTools tools;
    private final BrowserSettings settings;
    private final Map<ChatConversationId, BrowserProfile> profiles = new ConcurrentHashMap<>();
    /** Die Rückfrage-Fläche des Turns, der gerade auf diesem Thread ein Browser-Werkzeug ausführt. */
    private final ThreadLocal<ApprovalPromptPort> asking = new ThreadLocal<>();
    /** Der jüngste Turn je Chat; ein älterer räumt nicht mehr auf. */
    private final Map<ChatConversationId, Long> turns = new ConcurrentHashMap<>();
    private long nextTurn;
    /** Die Websuche der Chats im eigenen Suchbrowser ({@link #start}), sonst {@code null}. */
    private volatile AutoCloseable webSearch;
    /** Fragt bei der ersten internen Suche ohne Suchseite nach einer (Angelo 27.11.2026). */
    private volatile SearchPageQuestion searchPageQuestion = SearchPageQuestion.DIALOG;

    ChatWebBrowser(ChatBrowserPort browsers, DelegatingBrowserAccessGuard guard, BrowserSettings settings) {
        this.browsers = browsers;
        this.settings = settings;
        ApprovalPromptPort current = (request, timeoutMillis) -> {
            ApprovalPromptPort prompt = asking.get();
            return (prompt == null ? ApprovalPromptPort.unavailable() : prompt).ask(request, timeoutMillis);
        };
        guard.delegateTo(new BrowserAccessService(this::profile,
                profile -> BrowserAccessService.policyOf(settings.load().profile(profile)), () -> current, settings,
                ToolApprovalGate.DEFAULT_TIMEOUT_MILLIS));
        // Ändernde Aktionen fragen mit „Immer erlauben/verbieten“ je Website und merken die Antwort (Angelo 24.11.2026);
        // vorher fragte jede Aktion jedes Mal, weil es keine gemerkten Regeln gab.
        ChangeApprovals changes = new ChangeApprovals(settings, () -> current);
        this.tools = new BrowserMcpTools(browsers, documents(),
                new ToolApprovalGate(ApprovalRules::none, changes, ToolApprovalGate.DEFAULT_TIMEOUT_MILLIS));
    }

    /** Warum es zuletzt keinen Browser gab (Kanal und Grund), leer wenn er verfügbar war. */
    private static volatile String unavailable = "";

    /**
     * Startet die Browser der Chats; {@code null}, wenn der gewählte Browser hier nicht starten kann. Der Browser
     * selbst startet erst mit dem ersten Browser-Werkzeug eines Chats.
     */
    public static ChatWebBrowser start(Path dataDirectory, BrowserSettings settings) {
        String channel = settings.channel();
        AppTimeouts timeouts = AppTimeouts.shared();
        BrowserRuntime browserRuntime = new BrowserRuntime(BrowserRuntimeConfig.of(BrowserChannel.of(channel), settings.executable(channel),
                dataDirectory, timeouts.get(AppTimeouts.Value.BROWSER_START)));
        RuntimeBrowserLauncher launcher = new RuntimeBrowserLauncher(browserRuntime);
        String readiness = launcher.readiness().render();
        if (!readiness.startsWith("READY")) {
            String name = BrowserSettings.CHANNELS.getOrDefault(channel, channel);
            unavailable = readiness.startsWith("BROWSER_NOT_INSTALLED") ? name + " ist hier nicht installiert"
                    : name + " nicht verfügbar (" + readiness + ")";
            LOG.log(System.Logger.Level.WARNING, "Websuche im Chat: " + unavailable);
            return null;
        }
        unavailable = "";
        DelegatingBrowserAccessGuard guard = new DelegatingBrowserAccessGuard();
        // Die Suche aus Ask AI im eigenen Suchbrowser (Angelo 27.11.2026): Google im Serp4j-Suchbrowser, die übrigen
        // Suchmaschinen und die Suchseiten des Hauses in einem eigenen Browser; das Chat-Fenster öffnet nur Treffer.
        AtomicReference<ChatWebBrowser> built = new AtomicReference<>();
        SearchBrowserWebSearch webSearch = new SearchBrowserWebSearch(launcher, () -> !settings.searchWindowShown(),
                LegacyBrowserSearchDefaults::create, chat -> built.get().engines(chat));
        ChatBrowserRuntime runtime = new ChatBrowserRuntime(new PlaywrightChatEngine(launcher, false,
                timeouts.get(AppTimeouts.Value.BROWSER_NAVIGATION).toMillis()),
                new BrowserProfileStore(dataDirectory.resolve(PROFILES_DIRECTORY)), guard, webSearch);
        ChatWebBrowser browser = new ChatWebBrowser(runtime, guard, settings);
        browser.webSearch = webSearch;
        built.set(browser);
        return browser;
    }

    /** Fragt nach der Such-URL einer internen Suchseite; {@code null}, wenn der Benutzer abbricht. */
    public interface SearchPageQuestion {
        String ask(String problem);

        /** Der Dialog der App, auf dem EDT; der Werkzeug-Thread wartet auf die Antwort. */
        SearchPageQuestion DIALOG = problem -> {
            String[] answer = {null};
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> answer[0] =
                        com.aresstack.spaceharvester.app.ui.Dialogs.askText(
                                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),
                                INTERNAL_SEARCH_TITLE, problem == null ? INTERNAL_SEARCH_HINT
                                        : problem + "\n\n" + INTERNAL_SEARCH_HINT, ""));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException e) {
                LOG.log(System.Logger.Level.WARNING, "Frage nach der internen Suchseite nicht gezeigt: "
                        + e.getCause());
            }
            return answer[0];
        };
    }

    static final String INTERNAL_SEARCH_TITLE = "Interne Suche einrichten";
    static final String INTERNAL_SEARCH_HINT = "Für das Profil Intern ist noch keine Suchseite eingetragen. Gemeint "
            + "ist eine Suchseite des Unternehmens, die nur intern erreichbar ist, etwa das Unternehmensportal oder "
            + "das Intranet, nicht Google oder eine andere öffentliche Suchmaschine. Gib die Adresse ihrer "
            + "Ergebnisseite ein und schreibe {query} an die Stelle der Suchbegriffe, zum Beispiel "
            + "https://portal.example/suche?q={query}. Weitere Suchseiten trägst du unter Einstellungen › Browser ein.";

    /** Wie die erste interne Suche ohne Suchseite nachfragt (Tests ersetzen den Dialog). */
    void searchPageQuestion(SearchPageQuestion question) {
        searchPageQuestion = question == null ? SearchPageQuestion.DIALOG : question;
    }

    /**
     * Die Suchmaschinen des Chats nach seinem Profil. Hat Intern noch keine aktive Suchseite, fragt die erste interne
     * Suche nach einer und trägt sie ein; ohne Antwort sucht sie nichts.
     */
    BrowserSearchEngineSelection engines(ChatConversationId chat) {
        BrowserProfile profile = profile(chat);
        BrowserSearchEngineSelection engines = settings.engines(profile);
        if (profile != BrowserProfile.INTERNAL || !engines.resolvedEnabledEngines().isEmpty()) {
            return engines;
        }
        String problem = null;
        while (true) {
            String typed = searchPageQuestion.ask(problem);
            if (typed == null || typed.isBlank()) {
                return engines;
            }
            if (BrowserSearchEngineCatalog.isSearchPage(typed.strip())) {
                settings.addSearchPage(BrowserProfile.INTERNAL, typed.strip());
                return settings.engines(BrowserProfile.INTERNAL);
            }
            problem = "„" + typed.strip() + "“ ist keine Such-URL: Sie beginnt mit http:// oder https:// und enthält "
                    + "{query}.";
        }
    }

    /** Warum der letzte {@link #start} keinen Browser lieferte; leer, wenn er einen lieferte. */
    public static String unavailable() {
        return unavailable;
    }

    /** Text verlinkter Dokumente (PDF, Office) über Tika mit den Grenzen der Quellen ({@code assets.extraction.*}). */
    private static AttachmentTextExtractor documents() {
        return (fileName, content) -> {
            DocumentText text = com.aresstack.spaceharvester.app.deck.DocumentTexts.text();
            try {
                String type = text.detect(fileName, content).orElse("application/octet-stream");
                return text.extract(fileName, type, content).text();
            } catch (RuntimeException failed) {
                throw new AttachmentException(failed.getMessage() == null ? "Dokument nicht lesbar"
                        : failed.getMessage(), failed);
            }
        };
    }

    /**
     * Führt {@code call} (ein Browser-Werkzeug) so aus, dass seine Rückfragen (unbekannte Website, verändernde Aktion)
     * an {@code prompt} gehen, die Fläche des Chats, aus dem der Aufruf kommt. Die Werkzeuge fragen auf dem
     * aufrufenden Thread; ohne Fläche gilt „nicht freigegeben“.
     */
    public String asking(ApprovalPromptPort prompt, Supplier<String> call) {
        ApprovalPromptPort before = asking.get();
        asking.set(prompt == null ? ApprovalPromptPort.unavailable() : prompt);
        try {
            return call.get();
        } finally {
            if (before == null) {
                asking.remove();
            } else {
                asking.set(before);
            }
        }
    }

    /**
     * Die Browser-Werkzeuge des Chats {@code chatId}; der Chat wird hier gebunden, nie vom Modell. {@code browser_search}
     * sucht mit den Suchmaschinen des Profils: Intern nur die Suchseiten des Hauses, so gehen Suchbegriffe aus dem
     * Intranet nie an eine öffentliche Suchmaschine.
     */
    public List<McpToolContribution> tools(String chatId) {
        return tools.contributionsFor(new ChatConversationId(chatId));
    }

    /** Die Regel des Browserprofils für den Systemhinweis. */
    public String profileRule(String chatId) {
        return BrowserRules.browserProfile(profile(new ChatConversationId(chatId)).label());
    }

    /** Das Profil eines Chats, ohne Wahl Extern. */
    BrowserProfile profile(ChatConversationId chat) {
        BrowserProfile profile = chat == null ? null : profiles.get(chat);
        return profile == null ? BrowserProfile.EXTERNAL : profile;
    }

    /** Setzt das Profil des Chats (Intern oder Extern); gilt ab der nächsten Adresse. */
    public void profile(String chatId, boolean internal) {
        ChatConversationId chat = new ChatConversationId(chatId);
        if (internal) {
            profiles.put(chat, BrowserProfile.INTERNAL);
        } else {
            profiles.remove(chat);
        }
    }

    /** Parallele Tabs und größte zufällige Verzögerung von {@code browser_open_many} im Chat. */
    public void load(String chatId, int tabs, int delayMillis) {
        tools.setBatchLoad(new ChatConversationId(chatId), tabs, delayMillis);
    }

    /**
     * Unteragenten für {@code browser_open_many} mit Frage: je geladener Seite eine Auswertung, höchstens
     * {@code parallel} gleichzeitig (Angelo 24.11.2026); {@code null} schaltet sie ab.
     */
    public void pageEvaluator(String chatId,
                              com.aresstack.enterpriseai.proasteion.application.agent.BrowserPageEvaluator evaluator,
                              int parallel) {
        tools.setPageEvaluator(new ChatConversationId(chatId), evaluator, parallel);
    }

    /** Menschliche Eingabe (Tasten, Lesepausen) und Mausbewegungen im Browser des Chats. */
    public void humanEmulation(String chatId, boolean keys, boolean mouse) {
        tools.setHumanEmulation(new ChatConversationId(chatId), keys, mouse);
    }

    /**
     * Beginnt die Aufzeichnung einer Antwort; {@code listener} bekommt jede Änderung auf dem Werkzeug-Thread. Liefert
     * die Nummer des Turns für {@link #endTurn}. Wartet, bis ein älterer Turn des Chats seinen Browser geschlossen hat.
     */
    public synchronized long beginTurn(String chatId, Consumer<WebExplorationProgress> listener) {
        ChatConversationId chat = new ChatConversationId(chatId);
        long turn = ++nextTurn;
        turns.put(chat, turn);
        tools.beginTurn(chat, listener);
        return turn;
    }

    /**
     * Beendet die Aufzeichnung und schließt den Browser des Chats wie im Enterprise AI Client nach jeder Antwort,
     * außer ein Werkzeug hält ihn offen (Anmeldung, Captcha). Das Profil bleibt. Hat im Chat inzwischen ein neuer
     * Turn begonnen ({@code turn} ist nicht mehr der jüngste), bleibt alles, wie es ist, und es kommt {@code null}.
     */
    public synchronized WebExplorationProgress endTurn(String chatId, long turn) {
        ChatConversationId chat = new ChatConversationId(chatId);
        if (!Long.valueOf(turn).equals(turns.get(chat))) {
            return null;
        }
        turns.remove(chat);
        WebExplorationProgress last = tools.endTurn(chat);
        if (!tools.keepsBrowserOpen(chat)) {
            try {
                browsers.browserOf(chat).closeBrowser();
            } catch (RuntimeException failed) {
                LOG.log(System.Logger.Level.DEBUG, "Browser des Chats nicht geschlossen: "
                        + failed.getClass().getSimpleName());
            }
        }
        return last;
    }

    /** Löscht das Profil eines gelöschten Chats; Fehler werden nur protokolliert. */
    public void deleteProfile(String chatId) {
        try {
            browsers.deleteProfile(new ChatConversationId(chatId));
        } catch (BrowserException | RuntimeException failed) {
            LOG.log(System.Logger.Level.WARNING, "Browser-Profil des Chats nicht gelöscht: " + failed.getMessage());
        }
    }

    /** Beendet alle Browser beim Schließen der App, auch den Suchbrowser; die Profile bleiben. */
    public void shutdown() {
        browsers.releaseAll();
        AutoCloseable search = webSearch;
        if (search != null) {
            try {
                search.close();
            } catch (Exception failed) {
                LOG.log(System.Logger.Level.DEBUG, "Suchbrowser nicht beendet: " + failed.getClass().getSimpleName());
            }
        }
    }
}
