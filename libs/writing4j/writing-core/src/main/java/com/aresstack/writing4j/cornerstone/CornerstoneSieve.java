package com.aresstack.writing4j.cornerstone;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.scope.Fence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The sieve of the cornerstone candidates (measurements M8, M13 to M18): per term and run one call with the frame of
 * the book, the term and a few places where it stands; the model names its role. A run is one model, with or without
 * the fence, with one set of places; every run is one vote. One mechanism for every model and every basket: full
 * agreement is safe in, (almost) no vote is safe out, everything between is the uncertain middle the reader may sort.
 * A stronger model or cleaner sources shrink the middle by themselves; the limits are settings.
 */
public final class CornerstoneSieve {

    /** The role of a term for the book. */
    public enum Role { ECKPFEILER, UMFELD, ALLGEMEINWORT, FREMD }

    /** The settings; the defaults are those of the measurements. */
    public static final class Options {
        public static final int DEFAULT_PLACES = 2;
        public static final int DEFAULT_PLACE_CHARS = 300;
        public static final int DEFAULT_PLACE_ROUNDS = 2;
        public static final int DEFAULT_ANSWER_TOKENS = 2000;
        public static final double DEFAULT_SAFE_IN_SHARE = 1.0;
        public static final double DEFAULT_SAFE_OUT_SHARE = 1.0 / 3;

        private final int places;
        private final int placeChars;
        private final int placeRounds;
        private final int answerTokens;
        private final double safeInShare;
        private final double safeOutShare;
        private final int parallel;

        /**
         * @param places       places per term and call, from different sources
         * @param placeChars   characters of a place around the term
         * @param placeRounds  how many different sets of places each model judges (each one vote)
         * @param answerTokens the answer limit of a call (a thinking model needs room)
         * @param safeInShare  a term with at least this share of cornerstone votes is safe in
         * @param safeOutShare a term with at most this share of cornerstone votes is safe out
         * @param parallel     calls at once, the capacity of the model as the host gives it
         */
        public Options(int places, int placeChars, int placeRounds, int answerTokens, double safeInShare,
                       double safeOutShare, int parallel) {
            if (places < 1 || placeChars < 50 || placeRounds < 1 || answerTokens < 1 || parallel < 1
                    || safeInShare <= 0 || safeInShare > 1 || safeOutShare < 0 || safeOutShare >= safeInShare) {
                throw new IllegalArgumentException("places >= 1, placeChars >= 50, placeRounds >= 1, answerTokens >= 1, "
                        + "parallel >= 1, 0 <= safeOutShare < safeInShare <= 1 expected");
            }
            this.places = places;
            this.placeChars = placeChars;
            this.placeRounds = placeRounds;
            this.answerTokens = answerTokens;
            this.safeInShare = safeInShare;
            this.safeOutShare = safeOutShare;
            this.parallel = parallel;
        }

        public static Options defaults() {
            return new Options(DEFAULT_PLACES, DEFAULT_PLACE_CHARS, DEFAULT_PLACE_ROUNDS, DEFAULT_ANSWER_TOKENS,
                    DEFAULT_SAFE_IN_SHARE, DEFAULT_SAFE_OUT_SHARE, 1);
        }

        public Options withParallel(int value) {
            return new Options(places, placeChars, placeRounds, answerTokens, safeInShare, safeOutShare, value);
        }

        public Options withShares(double in, double out) {
            return new Options(places, placeChars, placeRounds, answerTokens, in, out, parallel);
        }

        public int getPlaceRounds() {
            return placeRounds;
        }

        public double getSafeInShare() {
            return safeInShare;
        }

        public double getSafeOutShare() {
            return safeOutShare;
        }
    }

    /** One vote source: a model, with or without the fence, with one set of places. */
    public static final class Run {
        private final MainModelChat chat;
        private final boolean withFence;
        private final int round;

        public Run(MainModelChat chat, boolean withFence, int round) {
            this.chat = chat;
            this.withFence = withFence;
            this.round = round;
        }

        public boolean isWithFence() {
            return withFence;
        }

        public String label() {
            return chat.modelName() + (withFence ? " mit Zaun" : " ohne Zaun") + " · Fundstellen " + (round + 1);
        }
    }

    /** Where a term stands after the sieve. */
    public enum Verdict { SICHER_DRIN, UNSICHER, SICHER_DRAUSSEN }

    /** The votes of one term. */
    public static final class Votes {
        private final List<Role> roles;
        private final int failed;

        Votes(List<Role> roles, int failed) {
            this.roles = Collections.unmodifiableList(new ArrayList<Role>(roles));
            this.failed = failed;
        }

        public List<Role> getRoles() {
            return roles;
        }

        public int getFailed() {
            return failed;
        }

        public int count(Role role) {
            int n = 0;
            for (Role r : roles) {
                n += r == role ? 1 : 0;
            }
            return n;
        }

        /** The share of votes for the given roles among the answered runs; {@code -1} without an answer. */
        public double share(Set<Role> wanted) {
            if (roles.isEmpty()) {
                return -1;
            }
            int n = 0;
            for (Role r : roles) {
                n += wanted.contains(r) ? 1 : 0;
            }
            return n / (double) roles.size();
        }
    }

    /** Called after each call: done of total. */
    public interface Progress {
        void calls(int done, int total);
    }

    private final Options options;
    private final boolean german;
    private final Consumer<String> log;

    public CornerstoneSieve(String language, Options options, Consumer<String> log) {
        this.options = options == null ? Options.defaults() : options;
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.log = log == null ? line -> {
        } : log;
    }

    /**
     * The runs for the models the host offers: per model with the fence and, when the fence says more than the
     * mission, without it; each with {@link Options#getPlaceRounds()} sets of places.
     */
    public List<Run> runs(List<MainModelChat> models, Fence fence) {
        List<Run> out = new ArrayList<Run>();
        for (MainModelChat model : models) {
            for (int round = 0; round < options.placeRounds; round++) {
                out.add(new Run(model, true, round));
                if (fence.hasBounds()) {
                    out.add(new Run(model, false, round));
                }
            }
        }
        return out;
    }

    /** The verdict of a term by its votes for the wanted roles. */
    public Verdict verdict(Votes votes, Set<Role> wanted) {
        double share = votes.share(wanted);
        if (share < 0) {
            return Verdict.UNSICHER;
        }
        if (share >= options.safeInShare - 1e-9) {
            return Verdict.SICHER_DRIN;
        }
        return share <= options.safeOutShare + 1e-9 ? Verdict.SICHER_DRAUSSEN : Verdict.UNSICHER;
    }

    /**
     * Every term in every run, {@link Options#parallel} calls at once.
     *
     * @param places the places of each term (by its key), passage id to text with the source, anchors first
     */
    public Map<String, Votes> judge(List<TermCandidates.Candidate> terms, Map<String, List<Place>> places,
                                    List<Run> runs, Fence fence, Progress progress) {
        final Map<String, List<Role>> roles = new ConcurrentHashMap<String, List<Role>>();
        final Map<String, AtomicInteger> failures = new ConcurrentHashMap<String, AtomicInteger>();
        for (TermCandidates.Candidate term : terms) {
            roles.put(term.getKey(), Collections.synchronizedList(new ArrayList<Role>()));
            failures.put(term.getKey(), new AtomicInteger());
        }
        final int total = terms.size() * runs.size();
        final AtomicInteger done = new AtomicInteger();
        if (total == 0) {
            return new LinkedHashMap<String, Votes>();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.parallel, total));
        List<Future<?>> running = new ArrayList<Future<?>>();
        try {
            for (final Run run : runs) {
                final String frame = frame(run.withFence ? fence : Fence.of(fence.getMission()));
                for (final TermCandidates.Candidate term : terms) {
                    final List<Place> shown = choose(places.get(term.getKey()), run.round);
                    running.add(pool.submit(() -> {
                        Role role = ask(run.chat, prompt(frame, term.getTerm(), shown));
                        if (role == null) {
                            failures.get(term.getKey()).incrementAndGet();
                        } else {
                            roles.get(term.getKey()).add(role);
                        }
                        if (progress != null) {
                            progress.calls(done.incrementAndGet(), total);
                        }
                    }));
                }
            }
            for (Future<?> result : running) {
                result.get();
            }
        } catch (ExecutionException crashed) {
            throw new IllegalStateException("sieve crashed", crashed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("sieve interrupted", interrupted);
        } finally {
            pool.shutdownNow();
        }
        Map<String, Votes> out = new LinkedHashMap<String, Votes>();
        int failed = 0;
        for (TermCandidates.Candidate term : terms) {
            List<Role> list = new ArrayList<Role>(roles.get(term.getKey()));
            Collections.sort(list);
            out.put(term.getKey(), new Votes(list, failures.get(term.getKey()).get()));
            failed += failures.get(term.getKey()).get();
        }
        log.accept("sieve: " + terms.size() + " terms, " + runs.size() + " runs, " + failed + " calls without a role");
        return out;
    }

    /** A place of a term: the passage, its source and the text around the term. */
    public static final class Place {
        private final String passageId;
        private final String sourceId;
        private final String text;

        public Place(String passageId, String sourceId, String text) {
            this.passageId = passageId;
            this.sourceId = sourceId;
            this.text = text;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getText() {
            return text;
        }
    }

    /**
     * The places of a term: per passage the window of {@code chars} around its first occurrence, one place per source
     * in the order of the passages (anchors first), then the remaining ones.
     */
    public static List<Place> places(TermCandidates.Candidate term, Map<String, TermCandidates.Text> passages,
                                     int chars) {
        return places(term, term.getPassageIds(), passages, chars);
    }

    /** The places of a term in the given passages only (those of one field), one per source first. */
    public static List<Place> places(TermCandidates.Candidate term, List<String> passageIds,
                                     Map<String, TermCandidates.Text> passages, int chars) {
        List<Place> first = new ArrayList<Place>();
        List<Place> more = new ArrayList<Place>();
        Set<String> sources = new LinkedHashSet<String>();
        String[] words = term.getTerm().toLowerCase(Locale.ROOT).split(" ");
        String needle = words[words.length - 1].length() > 4
                ? words[words.length - 1].substring(0, words[words.length - 1].length() - 1)
                : words[words.length - 1];
        for (String id : passageIds) {
            TermCandidates.Text passage = passages.get(id);
            if (passage == null) {
                continue;
            }
            Place place = new Place(id, passage.getSourceId(), window(passage.getText(), needle, chars));
            (sources.add(passage.getSourceId()) ? first : more).add(place);
        }
        first.addAll(more);
        return first;
    }

    public int placeChars() {
        return options.placeChars;
    }

    /** The places of one round: the round's slice of the places, from the start again when they run out. */
    List<Place> choose(List<Place> all, int round) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        List<Place> out = new ArrayList<Place>();
        int start = round * options.places;
        for (int i = 0; i < Math.min(options.places, all.size()); i++) {
            out.add(all.get((start + i) % all.size()));
        }
        return out;
    }

    static String window(String text, String needle, int chars) {
        String flat = text.replaceAll("\\s+", " ").trim();
        int at = flat.toLowerCase(Locale.ROOT).indexOf(needle);
        if (flat.length() <= chars) {
            return flat;
        }
        int from = Math.max(0, (at < 0 ? 0 : at) - chars / 2);
        int to = Math.min(flat.length(), from + chars);
        from = Math.max(0, to - chars);
        return flat.substring(from, to);
    }

    private String frame(Fence fence) {
        return fence.describe(german, Integer.MAX_VALUE).trim();
    }

    String prompt(String frame, String term, List<Place> places) {
        StringBuilder text = new StringBuilder();
        if (german) {
            text.append("Du hilfst bei der Vorbereitung eines Sachbuchs.\n\n").append(frame).append("\n\n")
                    .append("Unten steht ein Begriff aus einer Quellensammlung mit Fundstellen. Bestimme, welche Rolle "
                            + "er für dieses Buch hat:\n"
                            + "- eckpfeiler: ein konkreter Fachbegriff, den das Buch erklären muss (Gerät, Verfahren, "
                            + "Messgröße, Gefährdung, Rechtsbegriff oder Vorschrift) und der einen direkten Bezug zum "
                            + "Gegenstand des Buches hat.\n"
                            + "- umfeld: ein Fachbegriff aus einem Nachbargebiet ohne direkten Bezug zum Gegenstand des "
                            + "Buches.\n"
                            + "- allgemeinwort: ein Allerweltswort oder Oberbegriff ohne eigenen fachlichen Gehalt, zum "
                            + "Beispiel Maßnahmen, Systeme, Bereich, Unternehmen.\n"
                            + "- fremd: gehört nicht zum Thema, oder ist ein Seitenrest, ein Anbieter-, Orts- oder "
                            + "Eigenname.\n\n"
                            + "Antworte nur mit dem einen Wort der Rolle.\n\n")
                    .append("Begriff: ").append(term).append('\n');
        } else {
            text.append("You help to prepare a non-fiction book.\n\n").append(frame).append("\n\n")
                    .append("Below is a term from a collection of sources with places where it stands. Decide which "
                            + "role it has for this book:\n"
                            + "- eckpfeiler: a concrete technical term the book must explain (device, method, measure, "
                            + "hazard, legal term or rule) with a direct relation to the subject of the book.\n"
                            + "- umfeld: a technical term of a neighbouring field without a direct relation to the "
                            + "subject of the book.\n"
                            + "- allgemeinwort: an everyday word or generic term without its own technical content, "
                            + "for example measures, systems, area, companies.\n"
                            + "- fremd: does not belong to the subject, or is a page remnant, a vendor, place or proper "
                            + "name.\n\n"
                            + "Answer only with the one word of the role.\n\n")
                    .append("Term: ").append(term).append('\n');
        }
        for (Place place : places) {
            text.append("   - ").append(place.text).append('\n');
        }
        return text.toString();
    }

    /** One call; the role named last in the answer, {@code null} when the call failed or named none. */
    private Role ask(MainModelChat chat, String prompt) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user(prompt));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                MainModelChatResult result = chat.complete(messages, 0.0, options.answerTokens);
                Role role = result != null && result.isOk() ? roleOf(result.getText()) : null;
                if (role != null) {
                    return role;
                }
            } catch (RuntimeException failed) {
                log.accept("sieve call failed: " + failed.getClass().getSimpleName());
            }
        }
        return null;
    }

    /** The role word named last in an answer (as the measurements counted it). */
    static Role roleOf(String answer) {
        String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        Role found = null;
        int last = -1;
        for (Role role : Role.values()) {
            int at = text.lastIndexOf(role.name().toLowerCase(Locale.ROOT));
            if (at > last) {
                last = at;
                found = role;
            }
        }
        return found;
    }
}
