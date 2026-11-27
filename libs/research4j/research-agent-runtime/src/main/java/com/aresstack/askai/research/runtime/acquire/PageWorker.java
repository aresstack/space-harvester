package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.mailbox.AgentMailbox;
import com.aresstack.askai.research.runtime.team.mailbox.AgentMessage;
import com.aresstack.askai.research.runtime.team.mailbox.MessagesForYouBlock;
import com.aresstack.askai.research.runtime.team.mailbox.WorkerAnswer;
import com.aresstack.askai.research.runtime.team.mailbox.WorkerAnswerParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One page worker of the research team (plan T4, section 3): it gets one opened page, the node and the query it
 * belongs to, the candidate links and its inbox, and answers in plain labelled lines (SUMMARY, USEFUL, FOLLOW,
 * TO ORCHESTRATOR, TO WORKER n). Java does everything else: the prompt, the parsing with one repair, the routing
 * of its messages through the {@link AgentMailbox}, the decision about the source. No JSON, no state machine in
 * the prompt; one model call per page. Several page workers call the model at the same time, which is the point:
 * a small model answers many parallel requests with a higher total throughput.
 *
 * <p>Thread-safe per instance as long as one funnel run uses it (one page at a time); the mailbox is shared.</p>
 */
public final class PageWorker implements PageJudge {

    /** The model call of one page turn: system and user text in, the answer text out ({@code null} on failure). */
    public interface Model {
        String complete(List<Turn> messages);
    }

    /** One message of the page turn's conversation (the repair round adds two). */
    public static final class Turn {
        private final String role;
        private final String text;

        private Turn(String role, String text) {
            this.role = role;
            this.text = text;
        }

        public static Turn system(String text) {
            return new Turn("system", text);
        }

        public static Turn user(String text) {
            return new Turn("user", text);
        }

        public static Turn assistant(String text) {
            return new Turn("assistant", text);
        }

        public String getRole() {
            return role;
        }

        public String getText() {
            return text;
        }
    }

    /** Where the worker's lines go besides the mailbox (the technical log). */
    public interface Log {
        void line(String text);
    }

    static final String SYSTEM = "You inspect ONE web page for a book. You decide what the page states that matters "
            + "for the book's chapter node, whether the page is a useful source for it, and which of the listed links "
            + "are worth opening. Never invent content that is not on the page. You never search the web yourself; "
            + "if the node needs other sources, tell the orchestrator what to search for.\n"
            + "Answer with these lines only:\n"
            + "SUMMARY: what the page states that matters (2-5 sentences)\n"
            + "USEFUL: yes | partly | no - reason\n"
            + "FOLLOW: numbers of links worth opening, or none\n"
            + "SEARCH: a new search query the page suggests (a term, name, standard, study or publisher it names "
            + "that the book still needs), one per line, or none\n"
            + "TO ORCHESTRATOR: a message, or omit the line\n"
            + "TO WORKER <n>: a message for another worker, or omit the line";

    /** The page worker role: the Sources phase's worker slot (falls back to the Sources model), thinking off. */
    public static final ResearchRole ROLE = ResearchRole.builder("page-worker", ResearchModelSlots.PHASE_RESEARCH_WORKER)
            .purpose("reads one opened page for the research team and judges it as a source")
            .systemPrompt(SYSTEM)
            .output(RoleOutput.text("PageWorker labelled lines"))
            .thinking(com.aresstack.askai.agent.model.port.ThinkingMode.OFF)
            .build();

    private final Model model;
    private final AgentMailbox mailbox;
    private final String taskId;
    private final String nodeLabel;
    private final String bookTitle;
    private final int maxFollow;
    private final int pageCharacters;
    private final int maxSearch;
    private final String area;
    private final Log log;
    private final List<String> summaries = new ArrayList<String>();
    private int pages;
    private int useful;

    /**
     * @param mailbox        the team's mailbox; {@code taskId} must be registered there
     * @param maxFollow      at most this many links the worker may pick per page (setting)
     * @param pageCharacters at most this much page text goes into the prompt (setting)
     */
    public PageWorker(Model model, AgentMailbox mailbox, String taskId, String nodeLabel, String bookTitle,
                      int maxFollow, int pageCharacters, Log log) {
        this(model, mailbox, taskId, nodeLabel, bookTitle, maxFollow, pageCharacters, 0, "", log);
    }

    /**
     * @param maxSearch at most this many new search queries the worker may suggest per page (setting; 0 = none)
     * @param area      the Weidezaun of the book; the worker suggests only queries inside it ("" = none given)
     */
    public PageWorker(Model model, AgentMailbox mailbox, String taskId, String nodeLabel, String bookTitle,
                      int maxFollow, int pageCharacters, int maxSearch, String area, Log log) {
        this.maxSearch = Math.max(0, maxSearch);
        this.area = area == null ? "" : area.trim();
        if (model == null || mailbox == null || taskId == null) {
            throw new IllegalArgumentException("model, mailbox and taskId are required");
        }
        this.model = model;
        this.mailbox = mailbox;
        this.taskId = taskId;
        this.nodeLabel = nodeLabel == null ? "" : nodeLabel.trim();
        this.bookTitle = bookTitle == null ? "" : bookTitle.trim();
        this.maxFollow = Math.max(0, maxFollow);
        this.pageCharacters = Math.max(1, pageCharacters);
        this.log = log;
    }

    @Override
    public Verdict judge(Page page) {
        List<AgentMessage> inbox = mailbox.takeInbox(taskId);
        final List<Turn> conversation = new ArrayList<Turn>();
        conversation.add(Turn.system(SYSTEM));
        conversation.add(Turn.user(prompt(page, inbox)));
        String answer = model.complete(conversation);
        WorkerAnswerParser.Result parsed = new WorkerAnswerParser(maxFollow, page.getLinks().size(), maxSearch).parse(answer,
                new WorkerAnswerParser.Repair() {
                    public String retry(String instruction) {
                        List<Turn> again = new ArrayList<Turn>(conversation);
                        again.add(Turn.assistant(answer == null ? "" : answer));
                        again.add(Turn.user(instruction));
                        return model.complete(again);
                    }
                });
        if (!parsed.isOk()) {
            mailbox.release(inbox); // shown again at the next page
            log("no usable answer for " + page.getUrl() + " (" + parsed.getFailure() + ")");
            return null;
        }
        mailbox.acknowledge(inbox);
        WorkerAnswer worker = parsed.getAnswer();
        for (WorkerAnswer.Outgoing message : worker.getMessages()) {
            String reply = mailbox.send(taskId, message.getRecipient(), message.getText()).getReply();
            log("message to " + message.getRecipient() + ": " + reply);
        }
        boolean isUseful = worker.getUsefulness() == WorkerAnswer.Usefulness.YES
                || worker.getUsefulness() == WorkerAnswer.Usefulness.PARTLY;
        synchronized (this) {
            pages++;
            if (isUseful) {
                useful++;
                summaries.add(page.getUrl() + ": " + worker.getSummary());
            }
        }
        log(page.getUrl() + " USEFUL " + worker.getUsefulness().name().toLowerCase(java.util.Locale.ROOT)
                + (worker.getUsefulReason().isEmpty() ? "" : " - " + worker.getUsefulReason())
                + " FOLLOW " + worker.getFollow());
        return new Verdict(isUseful, worker.getUsefulReason(), worker.getFollow(), worker.getSearches());
    }

    /** The task id this worker is registered under in the mailbox (the planned query's request id). */
    public String taskId() {
        return taskId;
    }

    /** Ends this worker's task in the mailbox with what it found; idempotent there. */
    public String finish() {
        String summary;
        synchronized (this) {
            StringBuilder sb = new StringBuilder();
            sb.append(useful).append(" of ").append(pages).append(pages == 1 ? " page" : " pages")
                    .append(" useful for \"").append(nodeLabel).append("\".");
            for (String line : summaries) {
                sb.append(' ').append(line);
            }
            summary = sb.toString();
        }
        return mailbox.finishTask(taskId, summary);
    }

    /** The user text of one page turn (plan section 3). */
    String prompt(Page page, List<AgentMessage> inbox) {
        StringBuilder sb = new StringBuilder();
        if (!bookTitle.isEmpty()) {
            sb.append("Book: ").append(bookTitle).append(". ");
        }
        if (!nodeLabel.isEmpty()) {
            sb.append("Chapter node: ").append(nodeLabel).append(". ");
        }
        if (!area.isEmpty()) {
            sb.append("\nAREA OF THE BOOK (suggest searches only inside it, never for what it excludes):\n")
                    .append(area).append('\n');
        }
        sb.append("Query that found the page: ").append(page.getQuery()).append("\n");
        Integer number = mailbox.workerNumber(taskId);
        if (number != null) {
            sb.append("You are worker ").append(number).append(".\n");
        }
        String text = page.getText();
        boolean cut = text.length() > pageCharacters;
        sb.append("\nPAGE ").append(page.getUrl()).append(" (").append(page.getTitle()).append(", ")
                .append(text.length()).append(" characters").append(cut ? ", cut" : "").append("):\n")
                .append(cut ? text.substring(0, pageCharacters) : text).append("\n");
        List<String> links = page.getLinks();
        sb.append("\nLINKS (numbered):\n");
        if (links.isEmpty()) {
            sb.append("(none)\n");
        }
        for (int i = 0; i < links.size(); i++) {
            sb.append(i + 1).append(". ").append(links.get(i)).append('\n');
        }
        String messages = MessagesForYouBlock.render(inbox == null ? Collections.<AgentMessage>emptyList() : inbox);
        if (!messages.isEmpty()) {
            sb.append('\n').append(messages).append('\n');
        }
        sb.append("\nAnswer with the lines SUMMARY, USEFUL, FOLLOW (at most ").append(maxFollow).append(")");
        if (maxSearch > 0) {
            sb.append(", SEARCH (at most ").append(maxSearch).append(" new queries this page leads to, inside the "
                    + "area of the book, or none)");
        }
        sb.append(", and TO ORCHESTRATOR or TO WORKER <n> only when you have a message.");
        return sb.toString();
    }

    private void log(String text) {
        if (log != null) {
            log.line("[page-worker " + taskId + "] " + text);
        }
    }
}
