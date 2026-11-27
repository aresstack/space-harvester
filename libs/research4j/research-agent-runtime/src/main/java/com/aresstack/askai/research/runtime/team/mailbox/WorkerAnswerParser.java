package com.aresstack.askai.research.runtime.team.mailbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tolerant reader of a page worker's labelled answer (plan sections 2.4 and 3), in the manner of
 * {@code TeamAgentTurnParser}: labels in any case, with Markdown bullets or bold around them, text that runs on
 * over several lines, chatter before the first label. One bounded repair when {@code SUMMARY} is missing, then an
 * honest typed failure. No JSON anywhere.
 */
public final class WorkerAnswerParser {

    /** Why an answer could not be used. */
    public enum Failure { EMPTY_ANSWER, MISSING_SUMMARY }

    /** Asks the model once more; returns its new answer or {@code null} when that is not possible. */
    public interface Repair {
        String retry(String instruction);
    }

    /** The line sent with the one repair. */
    public static final String REPAIR_INSTRUCTION = "Your answer had no SUMMARY line. Answer again with these lines "
            + "only:\nSUMMARY: what the page states that matters (2-5 sentences)\nUSEFUL: yes | partly | no - reason\n"
            + "FOLLOW: numbers of links worth opening, or none\nSEARCH: a new search query this page suggests, or none\n"
            + "TO ORCHESTRATOR: a message, or omit the line";

    /** Parse outcome: an answer, or a typed failure. */
    public static final class Result {
        private final WorkerAnswer answer;
        private final Failure failure;
        private final boolean repaired;

        private Result(WorkerAnswer answer, Failure failure, boolean repaired) {
            this.answer = answer;
            this.failure = failure;
            this.repaired = repaired;
        }

        public boolean isOk() {
            return answer != null;
        }

        public WorkerAnswer getAnswer() {
            return answer;
        }

        public Failure getFailure() {
            return failure;
        }

        /** {@code true} when the answer came from the repair round. */
        public boolean isRepaired() {
            return repaired;
        }
    }

    private static final Pattern LABEL = Pattern.compile(
            "^[\\s>*_#`-]*(summary|useful|follow|search|to\\s+orchestrator|to\\s+the\\s+orchestrator|to\\s+coordinator"
                    + "|to\\s+worker\\s*#?\\s*\\d{1,4})[\\s*_`]*[:=\\-–—][\\s*_`]*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VERDICT = Pattern.compile("^(yes|partly|partially|partial|no)\\b[\\s,.;:\\-–—]*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final int maxFollow;
    private final int linkCount;
    private final int maxSearch;

    /**
     * @param maxFollow at most this many {@code FOLLOW} links are kept (plan: k, default 2)
     * @param linkCount numbers outside 1..linkCount are dropped
     */
    public WorkerAnswerParser(int maxFollow, int linkCount) {
        this(maxFollow, linkCount, 0);
    }

    /** @param maxSearch at most this many {@code SEARCH} queries are kept per page (0 = none) */
    public WorkerAnswerParser(int maxFollow, int linkCount, int maxSearch) {
        this.maxFollow = Math.max(0, maxFollow);
        this.linkCount = Math.max(0, linkCount);
        this.maxSearch = Math.max(0, maxSearch);
    }

    /** Parses without a repair round. */
    public Result parse(String text) {
        return parse(text, null);
    }

    /** Parses; if {@code SUMMARY} is missing, asks {@code repair} once and parses that answer. */
    public Result parse(String text, Repair repair) {
        Result first = parseOnce(text, false);
        if (first.isOk() || repair == null) {
            return first;
        }
        String second = repair.retry(REPAIR_INSTRUCTION);
        if (second == null) {
            return first;
        }
        return parseOnce(second, true);
    }

    private Result parseOnce(String text, boolean repaired) {
        if (text == null || text.trim().isEmpty()) {
            return new Result(null, Failure.EMPTY_ANSWER, repaired);
        }
        String summary = null;
        String useful = null;
        String follow = null;
        List<String> searches = new ArrayList<String>();
        List<String[]> outgoing = new ArrayList<String[]>();
        StringBuilder current = null;
        String currentKey;
        List<Object[]> fields = new ArrayList<Object[]>();
        for (String raw : text.replace("\r", "").split("\n")) {
            Matcher m = LABEL.matcher(raw);
            if (m.matches()) {
                currentKey = m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                current = new StringBuilder(stripMarkup(m.group(2)));
                fields.add(new Object[]{currentKey, current});
            } else if (current != null && !raw.trim().isEmpty()) {
                current.append(current.length() == 0 ? "" : " ").append(stripMarkup(raw));
            }
        }
        for (Object[] f : fields) {
            String key = (String) f[0];
            String value = f[1].toString().trim();
            if (key.equals("summary")) {
                if (summary == null) {
                    summary = value;
                }
            } else if (key.equals("useful")) {
                if (useful == null) {
                    useful = value;
                }
            } else if (key.equals("follow")) {
                if (follow == null) {
                    follow = value;
                }
            } else if (key.equals("search")) {
                // one query per line, or several on one line separated by ";" or " | "
                for (String query : value.split("\\s*[;|]\\s*")) {
                    String q = query.replaceAll("^[\"'“„]+|[\"'”“]+$", "").trim();
                    if (!q.isEmpty() && !isOmit(q) && searches.size() < maxSearch
                            && !containsIgnoreCase(searches, q)) {
                        searches.add(q);
                    }
                }
            } else if (!value.isEmpty() && !isOmit(value)) {
                String recipient = key.startsWith("to worker") ? key.substring(3) : "orchestrator";
                outgoing.add(new String[]{recipient, value});
            }
        }
        if (summary == null || summary.isEmpty()) {
            return new Result(null, Failure.MISSING_SUMMARY, repaired);
        }
        WorkerAnswer.Usefulness verdict = WorkerAnswer.Usefulness.UNSTATED;
        String reason = "";
        if (useful != null) {
            Matcher v = VERDICT.matcher(useful);
            if (v.matches()) {
                String word = v.group(1).toLowerCase(Locale.ROOT);
                verdict = word.equals("yes") ? WorkerAnswer.Usefulness.YES
                        : word.equals("no") ? WorkerAnswer.Usefulness.NO : WorkerAnswer.Usefulness.PARTLY;
                reason = v.group(2).trim();
            }
        }
        List<Integer> links = new ArrayList<Integer>();
        if (follow != null) {
            Matcher n = NUMBER.matcher(follow);
            while (n.find() && links.size() < maxFollow) {
                int number;
                try {
                    number = Integer.parseInt(n.group());
                } catch (NumberFormatException tooLong) {
                    continue;
                }
                if (number >= 1 && number <= linkCount && !links.contains(number)) {
                    links.add(number);
                }
            }
        }
        List<WorkerAnswer.Outgoing> messages = new ArrayList<WorkerAnswer.Outgoing>();
        for (String[] o : outgoing) {
            messages.add(new WorkerAnswer.Outgoing(o[0], o[1]));
        }
        return new Result(new WorkerAnswer(summary, verdict, reason, links, messages, searches), null, repaired);
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String v : values) {
            if (v.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOmit(String value) {
        String v = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();
        return v.equals("none") || v.equals("omit") || v.equals("omit the line") || v.equals("na") || v.equals("no message");
    }

    private static String stripMarkup(String s) {
        return s.replaceAll("^[\\s*_`]+|[\\s*_`]+$", "");
    }
}
