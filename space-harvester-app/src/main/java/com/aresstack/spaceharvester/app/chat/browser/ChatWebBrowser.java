package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.astu.acropolis.attachment.AttachmentException;
import com.aresstack.enterpriseai.astu.acropolis.attachment.AttachmentTextExtractor;
import com.aresstack.enterpriseai.astu.approval.ApprovalPromptPort;
import com.aresstack.enterpriseai.astu.approval.ApprovalRules;
import com.aresstack.enterpriseai.astu.approval.ToolApprovalGate;
import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.ChatBrowserPort;
import com.aresstack.enterpriseai.browser.firefox.FirefoxEngine;
import com.aresstack.enterpriseai.browser.runtime.BrowserProfileStore;
import com.aresstack.enterpriseai.browser.runtime.ChatBrowserRuntime;
import com.aresstack.enterpriseai.proasteion.application.agent.BrowserMcpTools;
import com.aresstack.enterpriseai.proasteion.application.browser.BrowserAccessService;
import com.aresstack.enterpriseai.proasteion.application.browser.DelegatingBrowserAccessGuard;
import com.aresstack.enterpriseai.proasteion.application.browser.WebExplorationProgress;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolContribution;
import com.aresstack.spaceharvester.application.assets.DocumentText;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der Browser der Chats für die Websuche, zusammengesetzt wie {@code AdapterAssembly.browsers} und die
 * Browser-Teile von {@code CompositionRoot} im Enterprise AI Client: ein sichtbares Firefox mit eigenem Profil je Chat
 * unter {@code <Datenordner>/browser-profiles}, gesteuert über WebDriver BiDi. Firefox kommt aus
 * {@code -Denterpriseai.firefox} oder dem üblichen Installationsort; ohne Firefox gibt es keine Websuche
 * ({@link #start} liefert {@code null}). Jede Adresse prüft die Richtlinie des aktiven Profils (Extern oder Intern);
 * unbekannte Websites und verändernde Aktionen fragt der Chat nach. Nach jeder Antwort schließt der Browser, das
 * Profil bleibt.
 */
public final class ChatWebBrowser {

    /** Systemeigenschaft mit dem Pfad zu Firefox, wie im Enterprise AI Client. */
    public static final String FIREFOX_PROPERTY = "enterpriseai.firefox";
    static final String PROFILES_DIRECTORY = "browser-profiles";
    private static final long FIREFOX_START_TIMEOUT_MILLIS = 30000L;
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

    /**
     * Startet die Browser der Chats, ohne Firefox {@code null}. Firefox selbst startet erst mit dem ersten
     * Browser-Werkzeug eines Chats.
     */
    public static ChatWebBrowser start(Path dataDirectory, BrowserSettings settings) {
        Path firefox = FirefoxEngine.locate(System.getProperty(FIREFOX_PROPERTY), System.getenv(),
                System.getProperty("os.name"));
        if (firefox == null) {
            return null;
        }
        DelegatingBrowserAccessGuard guard = new DelegatingBrowserAccessGuard();
        ChatBrowserRuntime runtime = new ChatBrowserRuntime(new FirefoxEngine(firefox, FIREFOX_START_TIMEOUT_MILLIS),
                new BrowserProfileStore(dataDirectory.resolve(PROFILES_DIRECTORY)), guard);
        return new ChatWebBrowser(runtime, guard, settings);
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

    /** Die Browser-Werkzeuge des Chats {@code chatId}; der Chat wird hier gebunden, nie vom Modell. */
    public List<McpToolContribution> tools(String chatId) {
        return tools.contributionsFor(new ChatConversationId(chatId));
    }

    /** Die Regel des Browserprofils mit seinen Such-URLs für den Systemhinweis. */
    public String profileRule(String chatId) {
        BrowserProfile profile = profile(new ChatConversationId(chatId));
        return BrowserRules.browserProfile(profile.label(), settings.load().profile(profile).searchUrls());
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

    /** Beendet alle Browser beim Schließen der App; die Profile bleiben. */
    public void shutdown() {
        browsers.releaseAll();
    }
}
