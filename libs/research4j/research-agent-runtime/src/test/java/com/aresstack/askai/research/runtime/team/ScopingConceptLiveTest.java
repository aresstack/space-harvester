package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.modelport.CentralModelDirectories;
import com.aresstack.askai.modelport.OllamaModelPort;
import com.aresstack.askai.research.agent.ConversationPolicyProjection;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ResearchScopeFenceView;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.host.ResearchRuntimeSettings;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.role.ResearchRoles;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The scoping regression's LIVE GATE against a real local model (default {@code gemma4:e2b}, override with
 * {@code -Daskai.live.main.model=...}): the real scoping prompt and grammar over the real model port
 * (ollama4j), the real {@link ConceptToolRounds} loop, and an in-memory concept host that answers like the
 * plugin's concept tools. It covers the model side of the reported failures:
 * <ol>
 *   <li>"hasenhaltung" yields no cards from the prompt's former examples (ESP32/FreeRTOS/Arduino) and no
 *       placeholders;</li>
 *   <li>a guinea-pig request yields cards that are actually executed through conceptAction;</li>
 *   <li>no visible answer spells an internal action (add_cards/offer/none);</li>
 *   <li>a second chat sees nothing of the first chat's topic;</li>
 *   <li>search offers and an exclusion still run through their commands;</li>
 *   <li>the conversation ends cleanly: no host fallback answer, no unanswered nudge.</li>
 * </ol>
 * The chat is wired like the plugin's {@code ResearchAgentSession} wires the productive one — this gate
 * once differed from the app in exactly the context the model answers on, and the exclusion that ran in
 * the app failed here ("I need the context you are referring to"):
 * <ul>
 *   <li>the assembler sees the host's AUTHORITATIVE {@code CURRENT_CONCEPT} block and the scope fence
 *       (mission = the user's first message, RULED OUT facets, the CONVERSATION POLICY block) before every
 *       inference, in the app's German working language;</li>
 *   <li>the scoping profile is the app's default ("Immer Suchvorschläge anbieten" off);</li>
 *   <li>the host answers in the plugin's receipt formats (concept document shape, add_cards fates,
 *       exclusion receipt JSON with the deterministic user sentence, offer receipt) and refuses the probe
 *       outside the closure check exactly like the host does.</li>
 * </ul>
 * Skips itself without a local Ollama on 127.0.0.1:11434 or without the model. The full app path (plugin
 * concept service, persisted Konzeptpapier, research scope) is checked separately over the AskAI test MCP
 * server. {@code -Daskai.live.repeat=N} repeats every case; the forwarded value is logged first.
 */
public class ScopingConceptLiveTest {

    private static final String BASE_URL = "http://127.0.0.1:11434";
    private static final String MODEL = System.getProperty("askai.live.main.model", "gemma4:e2b");
    private static final String[] FOREIGN = {"esp32", "esp-idf", "freertos", "arduino", "praxisprojekt"};
    /** Repeated runs per case ({@code -Daskai.live.repeat=N}): a passing case must pass every time. */
    private static final int REPEAT = Integer.getInteger("askai.live.repeat", 1);
    /** A bracketed instruction placeholder the model copied ("[Thema]") — never a card or a query. */
    private static final java.util.regex.Pattern BRACKET_PLACEHOLDER = java.util.regex.Pattern.compile(
            "\\[\\s*(?i:thema|topic|bereich|area|term|begriff|x|y|z)\\s*\\d*\\s*\\]");

    private static OllamaModelPort port;

    @BeforeClass
    public static void requireModel() {
        Assume.assumeTrue("no local Ollama with " + MODEL, modelInstalled(MODEL));
        // The gate's evidence line: the forwarded repeat count is visible BEFORE any case runs (the
        // triple repetition once never ran because the property never reached the test JVM).
        System.err.println("[scoping-live] model=" + MODEL + " repeat=" + REPEAT);
        port = CentralModelDirectories.fixedModelPort(BASE_URL, MODEL, Duration.ofMinutes(5));
    }

    @AfterClass
    public static void closePort() {
        if (port != null) {
            port.close();
        }
    }

    /** Words of which at least one card of a fitting rabbit-keeping concept names. */
    private static final String[] RABBIT_WORDS = {"hase", "kaninchen", "haltung", "gehege", "stall", "auslauf",
            "ernährung", "futter", "fütterung", "gesundheit", "pflege", "tierarzt", "unterbringung", "verhalten",
            "sozial", "artgerecht", "krankheit", "impf", "beschäftigung", "einrichtung"};
    /** Words of which at least one card of a fitting guinea-pig concept names. */
    private static final String[] GUINEA_PIG_WORDS = {"meerschwein", "haltung", "gehege", "stall", "käfig",
            "auslauf", "ernährung", "futter", "fütterung", "heu", "vitamin", "gesundheit", "pflege", "tierarzt",
            "unterbringung", "verhalten", "sozial", "artgerecht", "gruppe", "krankheit", "einrichtung"};

    @Test
    public void hasenhaltungBuildsFittingCardsWithoutForeignOnesOrPlaceholders() {
        for (int run = 1; run <= REPEAT; run++) {
            hasenhaltung(new Chat("hasen#" + run));
        }
    }

    private static void hasenhaltung(Chat chat) {
        chat.say("hasenhaltung");
        chat.report();
        assertFalse("the first turn must build cards through conceptAction: " + chat.host.calls,
                chat.host.cards.isEmpty());
        for (String card : chat.host.cards) {
            String lower = card.toLowerCase(Locale.ROOT);
            for (String foreign : FOREIGN) {
                assertFalse("foreign card from the prompt examples: " + card, lower.contains(foreign));
            }
        }
        chat.assertCardsFit(RABBIT_WORDS);
        assertTrue("the first turn offers search tags through conceptAction: " + chat.host.calls,
                chat.host.offers > 0);
        chat.assertNoPlaceholders();
        chat.assertCleanConversation();
    }

    @Test
    public void meerschweinchenCardsAreStoredAndAnExclusionStillRuns() {
        for (int run = 1; run <= REPEAT; run++) {
            meerschweinchen(new Chat("meerschweinchen#" + run));
        }
    }

    private static void meerschweinchen(Chat chat) {
        chat.say("Ich möchte mich über artgerechte Haltung und Ernährung von Meerschweinchen informieren.");
        assertFalse("cards must be executed through conceptAction, not only described: " + chat.host.calls,
                chat.host.cards.isEmpty());
        chat.assertCardsFit(GUINEA_PIG_WORDS);
        assertTrue("the first turn offers search tags through conceptAction: " + chat.host.calls,
                chat.host.offers > 0);
        chat.say("Zucht möchte ich nicht behandeln.");
        chat.report();
        chat.assertNoPlaceholders();
        chat.assertCleanConversation();
        boolean excluded = false;
        for (String call : chat.host.calls) {
            excluded |= call.startsWith("exclude") && call.toLowerCase(Locale.ROOT).contains("zucht");
        }
        assertTrue("the exclusion command still runs: " + chat.host.calls, excluded);
        assertTrue("the exclusion reached the scope fence: " + chat.host.fence,
                chat.host.fence.contains("RULED OUT") && chat.host.fence.contains("Zucht"));
    }

    @Test
    public void aSecondChatSeesNothingOfTheFirstChatsTopic() {
        for (int run = 1; run <= REPEAT; run++) {
            secondChat(run);
        }
    }

    private static void secondChat(int run) {
        Chat first = new Chat("first#" + run);
        first.say("hasenhaltung");
        Chat second = new Chat("second#" + run);
        second.say("Ich möchte mich über artgerechte Haltung und Ernährung von Meerschweinchen informieren.");
        second.report();
        for (List<ChatMessage> request : second.recording.requests) {
            for (ChatMessage message : request) {
                assertFalse("the second chat's context carries the first chat's topic",
                        message.getContent().toLowerCase(Locale.ROOT).contains("hasenhaltung"));
            }
        }
        for (String card : second.host.cards) {
            assertFalse("a card of the first chat: " + card, card.toLowerCase(Locale.ROOT).contains("hase"));
        }
        assertFalse("the second chat builds its own cards: " + second.host.calls, second.host.cards.isEmpty());
        second.assertNoPlaceholders();
    }

    // ------------------------------------------------------------------ one chat

    /** One research chat: its own TeamAgent, its own concept host, its own recorded model context. */
    private static final class Chat {
        final String name;
        final RecordingChat recording = new RecordingChat(PortMainModelChat.forRole(port,
                ResearchRoles.CONVERSATION, Duration.ofMinutes(5)));
        final InMemoryConceptHost host = new InMemoryConceptHost();
        /**
         * The app's wiring (ResearchAgentSession): the default scoping profile ("Immer
         * Suchvorschläge anbieten" off, concept tools on), the session's German working language,
         * and the assembler fed with the host's persisted concept block and the authoritative
         * scope fence BEFORE every inference — the model never rebuilds either from the chat.
         */
        final ResearchTeamAgent agent = new ResearchTeamAgent(recording,
                PhaseAssistantProfileRegistry.defaults(false, true),
                new PhaseContextAssembler(new PhaseContextAssembler.CurrentLanguage() {
                    public String displayName() {
                        return "German";
                    }
                }).withCurrentConcept(new PhaseContextAssembler.CurrentConcept() {
                    public String rendered() {
                        return TeamAgentPlaybook.currentConceptContext(host.readWhole(), true);
                    }
                }).withCurrentScope(new PhaseContextAssembler.CurrentScope() {
                    public String rendered() {
                        return host.fence;
                    }
                }));
        final List<String> visible = new ArrayList<String>();
        final List<String> trace = new ArrayList<String>();
        final TeamAgentStateView view = new TeamAgentStateView("scoping", "running",
                Collections.<String>emptyList());
        boolean offered;
        int printed;

        Chat(String name) {
            this.name = name;
        }

        void say(String userText) {
            // The host's per-turn bookkeeping BEFORE the turn: mission, policy stage, fence.
            host.beginUserTurn(userText);
            System.err.println("[scoping-live " + name + "] user turn: policy=" + host.stage
                    + " scope revision=" + host.draft.getRevision() + " cards=" + host.cards.size());
            TeamAgentResult result = ConceptToolRounds.run(agent.respond(userText, view),
                    new ConceptToolRounds.FollowUpTurn() {
                        public TeamAgentResult run(String feedback) {
                            return agent.internalTurn(feedback, view);
                        }

                        public TeamAgentResult run(String feedback, String requiredActionType) {
                            return agent.internalTurn(feedback, view, requiredActionType);
                        }
                    }, host,
                    ConceptToolRounds.DEFAULT_MAX_TOOL_ROUNDS, ConceptToolRounds.DEFAULT_MAX_REPAIR_ATTEMPTS,
                    true, null, line -> trace.add(line), !offered,
                    ConceptTurnPolicy.modeFor(userText), false, null, userText);
            for (String line : trace.subList(printed, trace.size())) {
                System.err.println("[scoping-live " + name + "] concept " + line);
            }
            printed = trace.size();
            assertTrue("turn failed: " + result.getStatus() + " " + result.getDetail(), result.isOk());
            offered |= host.offers > 0;
            visible.add(result.getOutput().getAssistantMessage());
            System.err.println("[scoping-live " + name + "] visible: "
                    + result.getOutput().getAssistantMessage().replace('\n', ' '));
        }

        /** At least one card names the topic's own areas (a loose word list — no exact model output). */
        void assertCardsFit(String[] topicWords) {
            for (String card : host.cards) {
                String lower = card.toLowerCase(Locale.ROOT);
                for (String word : topicWords) {
                    if (lower.contains(word)) {
                        return;
                    }
                }
            }
            throw new AssertionError("no card names an area of the topic: " + host.cards);
        }

        /** No stored card and no offered query is a placeholder ("<…>", "[Thema]", "...", "…") or empty. */
        void assertNoPlaceholders() {
            for (String card : host.cards) {
                assertReal("placeholder became a card", card);
            }
            for (String query : host.offeredQueries) {
                assertReal("placeholder became a search tag", query);
            }
        }

        private static void assertReal(String what, String value) {
            String trimmed = value.trim();
            assertFalse(what + ": " + value, trimmed.isEmpty() || trimmed.contains("<")
                    || trimmed.contains(">") || trimmed.contains("...") || trimmed.contains("…")
                    || BRACKET_PLACEHOLDER.matcher(trimmed).find());
        }

        /**
         * The conversation ends cleanly: no visible answer spells an internal action, none is the host's
         * fallback for an unexecuted action, and the last answer is a real reply to the user.
         */
        void assertCleanConversation() {
            for (String message : visible) {
                assertNull("visible answer spells an internal action: " + message,
                        VisibleAssistantMessageValidator.actionLeakIn(message));
            }
            for (String line : trace) {
                assertFalse("the host had to replace a leaked answer: " + line,
                        line.contains("host answer replaces"));
            }
            String last = visible.get(visible.size() - 1).trim();
            assertTrue("the last answer is a real reply: " + last, last.length() >= 20);
        }

        void report() {
            System.err.println("[scoping-live " + name + "] model=" + MODEL + " cards=" + host.cards
                    + " offers=" + host.offers + " offeredQueries=" + host.offeredQueries
                    + " excluded=" + host.excludedLabels + " calls=" + host.calls);
            if (!recording.requests.isEmpty()) {
                // The context the model answered on, compacted: what a failing turn actually saw.
                List<ChatMessage> last = recording.requests.get(recording.requests.size() - 1);
                System.err.println("[scoping-live " + name + "] last model context (" + last.size()
                        + " messages):");
                for (ChatMessage message : last) {
                    String text = message.getContent().replace("\r", "").replace('\n', ' ');
                    System.err.println("[scoping-live " + name + "]   " + message.getRole() + ": "
                            + (text.length() > 180 ? text.substring(0, 180) + "…" : text));
                }
            }
        }
    }

    /** Records every model context of one chat. */
    private static final class RecordingChat implements MainModelChat {
        final MainModelChat delegate;
        final List<List<ChatMessage>> requests = new ArrayList<List<ChatMessage>>();

        RecordingChat(MainModelChat delegate) {
            this.delegate = delegate;
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            requests.add(new ArrayList<ChatMessage>(messages));
            return delegate.complete(messages, temperature, maxOutputTokens);
        }

        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                                int maxOutputTokens, String schemaJson) {
            requests.add(new ArrayList<ChatMessage>(messages));
            return delegate.completeJson(messages, temperature, maxOutputTokens, schemaJson);
        }

        public String modelName() {
            return delegate.modelName();
        }
    }

    /**
     * Answers like the plugin's concept tools and scope system, in THEIR formats: the concept document in
     * the persisted shape, add_cards receipts naming every input's fate, the exclusion receipt JSON with
     * the host's deterministic user sentence, the offer receipt, and the probe refusal outside the closure
     * check. It also keeps the scope draft (mission = the first user message, exclusions as RULED OUT
     * facets) and projects the conversation policy once per user turn, so the fence the model sees is the
     * app's.
     */
    private static final class InMemoryConceptHost implements ConceptToolRounds.ConceptTool {
        /** One concept card: its children in document order (a leaf has none). */
        private static final class Node {
            final Map<String, Node> children = new LinkedHashMap<String, Node>();
        }

        final Node root = new Node();
        /** Every card name the concept holds, in creation order (renames replace the name). */
        final List<String> cards = new ArrayList<String>();
        final List<String> calls = new ArrayList<String>();
        final List<String> offeredQueries = new ArrayList<String>();
        final List<String> excludedLabels = new ArrayList<String>();
        ResearchScopeDraft draft = ResearchScopeDraft.empty();
        ConversationPolicyProjection.Stage stage = ConversationPolicyProjection.Stage.OPEN_EXPLORATION;
        String fence = "";
        long revision;
        int offers;
        private int cardsAtLastTurn = -1;
        private int turnsWithoutNewCards;
        private int conflicts;

        /**
         * The host's bookkeeping before a user turn (ResearchAgentSession.handleUserMessage): the first
         * message IS the mission, the ratified policy is projected over the current snapshot, and the
         * fence is published with the policy block.
         */
        void beginUserTurn(String userText) {
            String mission = userText == null ? "" : userText.trim();
            if (draft.getMission().isEmpty() && !mission.isEmpty()) {
                draft = draft.toBuilder().mission(mission.length() > 300 ? mission.substring(0, 300) : mission)
                        .nextRevision().build();
            }
            int count = countNodes(root);
            turnsWithoutNewCards = cardsAtLastTurn < 0 || count > cardsAtLastTurn ? 0 : turnsWithoutNewCards + 1;
            cardsAtLastTurn = count;
            stage = ConversationPolicyProjection.stageOf(new ConversationPolicyProjection.Inputs(
                    !draft.getMission().isEmpty(), count, turnsWithoutNewCards, false, false, false, null,
                    false, 0, ResearchRuntimeSettings.policySparseCards(),
                    ResearchRuntimeSettings.policyNoveltyTurns()));
            publishFence();
        }

        private void publishFence() {
            fence = ResearchScopeFenceView.render(draft) + ConversationPolicyProjection.hintBlock(stage, false);
        }

        /** The whole-concept read as the concept_read tool returns it: revision line + document JSON. */
        String readWhole() {
            return "revision=" + revision + "\n" + documentJson();
        }

        public String call(ConceptAction action) throws ToolInvoker.ToolFailure {
            calls.add(action.describe());
            switch (action.getType()) {
                case READ:
                    return read(action.getPath());
                case ADD_CARDS:
                    return addCards(action.getParent(), names(action.getNamesJson()));
                case RENAME:
                    return rename(action.getPath(), action.getName());
                case OFFER: {
                    offers++;
                    List<String> queries = queriesOf(action.getSuggestionsJson());
                    offeredQueries.addAll(queries);
                    return "{\"result\":\"OFFERED\",\"count\":" + queries.size() + "}";
                }
                case EXCLUDE:
                    return exclude(action.getTopic() == null ? "" : action.getTopic().trim());
                case PROBE:
                    if (stage != ConversationPolicyProjection.Stage.CLOSURE_CHECK) {
                        // The host refuses the closure-check sensor outside CLOSURE_CHECK — machinery.
                        throw new ToolInvoker.ToolFailure(ConversationPolicyProjection.probeRefusal(stage));
                    }
                    throw new ToolInvoker.ToolFailure("PROBE_UNAVAILABLE: no embedding model is configured"
                            + " — tell the user the measurement is unavailable; do not guess.");
                default:
                    throw new ToolInvoker.ToolFailure("not supported by the live gate host: "
                            + action.describe());
            }
        }

        private String read(List<String> path) throws ToolInvoker.ToolFailure {
            if (path.isEmpty()) {
                return readWhole();
            }
            Node parent = path.size() == 1 ? root : resolve(path.subList(0, path.size() - 1));
            String name = path.get(path.size() - 1);
            Node node = parent == null ? null : parent.children.get(name);
            if (node == null) {
                throw new ToolInvoker.ToolFailure("NOT_FOUND: no card at path " + path
                        + " — read the whole concept (path=[]) to see the existing card names.");
            }
            StringBuilder sb = new StringBuilder("revision=").append(revision).append('\n');
            if (path.size() > 1) {
                sb.append("parent=").append(path.get(path.size() - 2)).append('\n');
            }
            List<String> siblings = new ArrayList<String>(parent.children.keySet());
            siblings.remove(name);
            if (!siblings.isEmpty()) {
                sb.append("siblings=").append(String.join(", ", siblings)).append('\n');
            }
            return sb.append('{').append(quote(name)).append(":[").append(childrenJson(node)).append("]}")
                    .toString();
        }

        private String addCards(List<String> parentPath, List<String> names) throws ToolInvoker.ToolFailure {
            Node parent = root;
            String createdParent = null;
            if (!parentPath.isEmpty()) {
                parent = resolve(parentPath);
                if (parent == null) {
                    // A unique existing card name is enough — the host resolves the full path itself.
                    parent = byUniqueName(root, parentPath.get(parentPath.size() - 1));
                }
                if (parent == null && parentPath.size() == 1) {
                    // A missing single parent is created together with its cards (CREATED_PARENT).
                    createdParent = parentPath.get(0);
                    parent = new Node();
                    root.children.put(createdParent, parent);
                    cards.add(createdParent);
                }
                if (parent == null) {
                    throw new ToolInvoker.ToolFailure("PARENT_NOT_FOUND: no card at path " + parentPath
                            + " — read the concept (path=[]) and use the existing card names.");
                }
            }
            List<String> lines = new ArrayList<String>();
            boolean changed = createdParent != null;
            if (createdParent != null) {
                lines.add("CREATED_PARENT: " + createdParent);
            }
            for (String name : names) {
                if (isBlacklisted(name)) {
                    lines.add("SUPPRESSED_BY_SCOPE: " + name);
                } else if (parent.children.containsKey(name)) {
                    lines.add("ALREADY_PRESENT: " + name);
                } else {
                    parent.children.put(name, new Node());
                    cards.add(name);
                    lines.add("ADDED: " + name);
                    changed = true;
                }
            }
            if (changed) {
                revision++;
            }
            StringBuilder receipt = new StringBuilder(changed ? "APPLIED" : "NO_CHANGE")
                    .append(" revision=").append(revision);
            for (String line : lines) {
                receipt.append('\n').append(line);
            }
            return receipt.toString();
        }

        private String rename(List<String> path, String newName) throws ToolInvoker.ToolFailure {
            Node parent = path.size() == 1 ? root : resolve(path.subList(0, path.size() - 1));
            String old = path.get(path.size() - 1);
            if (parent == null || !parent.children.containsKey(old) || newName == null
                    || newName.trim().isEmpty()) {
                throw new ToolInvoker.ToolFailure("NOT_FOUND: no card at path " + path
                        + " — read the whole concept (path=[]) to see the existing card names.");
            }
            Map<String, Node> renamed = new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Node> entry : parent.children.entrySet()) {
                renamed.put(entry.getKey().equals(old) ? newName.trim() : entry.getKey(), entry.getValue());
            }
            parent.children.clear();
            parent.children.putAll(renamed);
            int index = cards.indexOf(old);
            if (index >= 0) {
                cards.set(index, newName.trim());
            }
            revision++;
            return "APPLIED revision=" + revision + "\nRENAMED: " + old + " -> " + newName.trim();
        }

        /** The exclude_topic receipt: facet RULED OUT, fence republished, deterministic German sentence. */
        private String exclude(String label) {
            String facetId = slugOf(label);
            if (facetId.isEmpty()) {
                return "{\"result\":\"REJECTED\",\"reason\":\"the topic yields no technical id — pass the "
                        + "user's term\"}";
            }
            draft = draft.toBuilder().putFacet(new ScopeFacet(facetId, label, ScopeFacet.Status.EXCLUDED,
                    "explicit user exclusion")).nextRevision().build();
            excludedLabels.add(label);
            publishFence();
            StringBuilder userMessage = new StringBuilder("„" + label
                    + "“ wurde ausgeschlossen und wird bei der Recherche unterdrückt.");
            StringBuilder json = new StringBuilder("{\"result\":\"EXCLUDED\",\"facetId\":").append(quote(facetId))
                    .append(",\"label\":").append(quote(label));
            List<String> conflict = exactPathOf(root, label, new ArrayList<String>());
            if (conflict == null) {
                json.append(",\"requiredResponse\":\"NONE\"");
            } else if (!resolve(conflict).children.isEmpty()) {
                json.append(",\"requiredResponse\":\"NONE\",\"conceptBranchNote\":\"the term matches a concept "
                        + "BRANCH with sub-cards — removal is manual editor work, no conflict question is asked\"");
                userMessage.append(" Der Begriff steht außerdem als Zweig mit Unterkarten im Konzept — dort "
                        + "entfernen ist bis zum Tree-Editor Handarbeit im Konzept-Editor; die Recherche dazu "
                        + "bleibt unabhängig davon unterdrückt.");
            } else {
                String conflictId = "conflict-" + (++conflicts);
                json.append(",\"conceptConflict\":{\"conflictId\":").append(quote(conflictId)).append(",\"path\":[");
                for (int i = 0; i < conflict.size(); i++) {
                    json.append(i > 0 ? "," : "").append(quote(conflict.get(i)));
                }
                json.append("]},\"requiredResponse\":\"INFORM_AND_ASK_REMOVE\"");
                userMessage.append(conflict.size() >= 2
                        ? " Der Begriff steht außerdem noch im Konzept unter „"
                                + conflict.get(conflict.size() - 2) + "“."
                        : " Der Begriff steht außerdem noch auf oberster Ebene im Konzept.")
                        .append(" Soll er dort ebenfalls entfernt werden?");
            }
            return json.append(",\"userMessage\":").append(quote(userMessage.toString())).append('}').toString();
        }

        /** The blacklist rule of concept_add_cards: an excluded label matches a card name exactly. */
        private boolean isBlacklisted(String name) {
            String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            for (String label : excludedLabels) {
                if (normalized.equals(label.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        // ---------------------------------------------------------------- tree helpers

        private Node resolve(List<String> path) {
            Node node = root;
            for (String segment : path) {
                node = node.children.get(segment);
                if (node == null) {
                    return null;
                }
            }
            return node;
        }

        private static Node byUniqueName(Node node, String name) {
            Node found = null;
            for (Map.Entry<String, Node> child : node.children.entrySet()) {
                Node hit = child.getKey().equals(name) ? child.getValue() : byUniqueName(child.getValue(), name);
                if (hit != null) {
                    if (found != null) {
                        return null; // ambiguous
                    }
                    found = hit;
                }
            }
            return found;
        }

        /** The exact-name (case-insensitive) path of a card, or null — the host's concept conflict rule. */
        private static List<String> exactPathOf(Node node, String label, List<String> prefix) {
            for (Map.Entry<String, Node> child : node.children.entrySet()) {
                List<String> path = new ArrayList<String>(prefix);
                path.add(child.getKey());
                if (child.getKey().trim().equalsIgnoreCase(label.trim())) {
                    return path;
                }
                List<String> deeper = exactPathOf(child.getValue(), label, path);
                if (deeper != null) {
                    return deeper;
                }
            }
            return null;
        }

        private static int countNodes(Node node) {
            int count = 0;
            for (Node child : node.children.values()) {
                count += 1 + countNodes(child);
            }
            return count;
        }

        /** The persisted document shape: {"title":"","subtitle":"","concept":[{"A":[],"B":[{"C":[]}]}]}. */
        private String documentJson() {
            return "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[" + childrenJson(root) + "]}";
        }

        private static String childrenJson(Node node) {
            if (node.children.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("{");
            int index = 0;
            for (Map.Entry<String, Node> child : node.children.entrySet()) {
                sb.append(index++ > 0 ? "," : "").append(quote(child.getKey())).append(":[")
                        .append(childrenJson(child.getValue())).append(']');
            }
            return sb.append('}').toString();
        }

        private static String quote(String value) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\');
                }
                sb.append(c == '\n' ? ' ' : c);
            }
            return sb.append('"').toString();
        }

        /** The host's facet id from a human term (lowercase ascii, umlauts transliterated, '-' gaps). */
        private static String slugOf(String label) {
            String lower = label.toLowerCase(Locale.ROOT)
                    .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
            StringBuilder sb = new StringBuilder();
            boolean gap = false;
            for (int index = 0; index < lower.length() && sb.length() < 40; index++) {
                char character = lower.charAt(index);
                if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                    if (gap && sb.length() > 0) {
                        sb.append('-');
                    }
                    sb.append(character);
                    gap = false;
                } else {
                    gap = true;
                }
            }
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static List<String> names(String json) {
            List<String> names = new ArrayList<String>();
            Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(json);
            if (parsed instanceof List) {
                for (Object name : (List<Object>) parsed) {
                    names.add(String.valueOf(name));
                }
            }
            return names;
        }

        @SuppressWarnings("unchecked")
        private static List<String> queriesOf(String json) {
            List<String> queries = new ArrayList<String>();
            Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(json);
            if (parsed instanceof List) {
                for (Object element : (List<Object>) parsed) {
                    if (element instanceof Map && ((Map<String, Object>) element).get("query") != null) {
                        queries.add(String.valueOf(((Map<String, Object>) element).get("query")));
                    }
                }
            }
            return queries;
        }
    }

    private static boolean modelInstalled(String model) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + "/api/tags").openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            InputStream in = connection.getInputStream();
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();
            return body.contains("\"" + model + "\"");
        } catch (Exception unreachable) {
            return false;
        }
    }
}
