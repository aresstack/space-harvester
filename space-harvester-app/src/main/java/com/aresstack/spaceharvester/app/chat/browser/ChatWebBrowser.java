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
import com.aresstack.spaceharvester.source.TikaDocumentText;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    private volatile ApprovalPromptPort prompt = ApprovalPromptPort.unavailable();

    ChatWebBrowser(ChatBrowserPort browsers, DelegatingBrowserAccessGuard guard, BrowserSettings settings) {
        this.browsers = browsers;
        this.settings = settings;
        ApprovalPromptPort current = (request, timeoutMillis) -> prompt.ask(request, timeoutMillis);
        guard.delegateTo(new BrowserAccessService(this::profile,
                profile -> BrowserAccessService.policyOf(settings.load().profile(profile)), () -> current, settings,
                ToolApprovalGate.DEFAULT_TIMEOUT_MILLIS));
        this.tools = new BrowserMcpTools(browsers, documents(),
                new ToolApprovalGate(ApprovalRules::none, current, ToolApprovalGate.DEFAULT_TIMEOUT_MILLIS));
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

    /** Text verlinkter Dokumente (PDF, Office) über Tika wie die Dokumente der Quellen. */
    private static AttachmentTextExtractor documents() {
        DocumentText text = new TikaDocumentText();
        return (fileName, content) -> {
            try {
                String type = text.detect(fileName, content).orElse("application/octet-stream");
                return text.extract(fileName, type, content).text();
            } catch (RuntimeException failed) {
                throw new AttachmentException(failed.getMessage() == null ? "Dokument nicht lesbar"
                        : failed.getMessage(), failed);
            }
        };
    }

    /** Wohin Rückfragen gehen (unbekannte Website, verändernde Aktion); ohne Fläche gilt „nicht freigegeben“. */
    public void prompt(ApprovalPromptPort prompt) {
        this.prompt = prompt == null ? ApprovalPromptPort.unavailable() : prompt;
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

    /** Menschliche Eingabe (Tasten, Lesepausen) und Mausbewegungen im Browser des Chats. */
    public void humanEmulation(String chatId, boolean keys, boolean mouse) {
        tools.setHumanEmulation(new ChatConversationId(chatId), keys, mouse);
    }

    /** Beginnt die Aufzeichnung einer Antwort; {@code listener} bekommt jede Änderung auf dem Werkzeug-Thread. */
    public WebExplorationProgress beginTurn(String chatId, Consumer<WebExplorationProgress> listener) {
        return tools.beginTurn(new ChatConversationId(chatId), listener);
    }

    /**
     * Beendet die Aufzeichnung und schließt den Browser des Chats wie im Enterprise AI Client nach jeder Antwort,
     * außer ein Werkzeug hält ihn offen (Anmeldung, Captcha). Das Profil bleibt.
     */
    public WebExplorationProgress endTurn(String chatId) {
        ChatConversationId chat = new ChatConversationId(chatId);
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
