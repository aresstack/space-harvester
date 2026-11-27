package com.aresstack.writing4j.sourcerating;

import com.aresstack.knowledge4j.model.ThinkingMode;
import com.aresstack.knowledge4j.run.Pieces;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.writing4j.engine.WritingSlots;
import com.aresstack.knowledge4j.model.role.ModelRole;
import com.aresstack.knowledge4j.model.role.RoleOutput;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Component 16 of the build plan (rev 13, 21.11.2026): the source rating. One model call per source group (the
 * sources of one host), not per passage: it reads address, title, host and the first passage of the group's first
 * source with text and returns the data type {@link SourceRating} (kind, jurisdiction, date, self-interest,
 * publisher, reason).
 * It describes and filters nothing. The list of known domains may suggest kind and jurisdiction; the model decides and
 * gives its reason, and both stay recorded.
 *
 * <p><b>Source profile against the Weidezaun.</b> Given a {@link Fence}, every source is rated on its own (a
 * document, not a host) and the prompt shows the fence and the headings of the source's passages. The answer then adds
 * the document level of the profile ({@link SourceRating.Fit}): what the source is about, whether it lies inside the
 * fence, is mixed (parts inside, parts outside, with the headings outside) or clearly outside, or is unclear. Only the
 * caller decides what follows from it; a mixed or unclear source is never dropped here.</p>
 *
 * <p>Rules of the build plan: a slot of its own ({@link WritingSlots#PHASE_RESEARCH_SOURCES}; gemma4:12b, see
 * there), a fixed answer schema, thinking off, temperature 0. A failed call or an answer that is no valid rating gets
 * one repair attempt, then the group is {@link SourceRating.Status#FEHLER} with the reason and is offered again later;
 * after {@link Options#STOP_AFTER_FAILURES} failed groups in a row the remaining groups are not asked (FEHLER too, with
 * the last cause, so that a model that cannot be loaded or reached shows as such). The rater keeps no state between
 * calls; it is as thread-safe as the injected chat.</p>
 */
public final class SourceRater {

    /** The stage name the ratings run under in {@link Pieces}. */
    public static final String STAGE = "quellensteckbriefe";

    /** How the rater works; immutable. */
    public static final class Options {
        /** Default calls at the same time (to be measured per role; build plan, models and graphics card). */
        public static final int DEFAULT_PARALLEL = 4;
        /** Default characters of the first passage in the prompt. */
        public static final int DEFAULT_PASSAGE_CHARS = 1200;
        /** After this many failed groups in a row the model is asked no more in that pass. */
        public static final int STOP_AFTER_FAILURES = 3;

        private final int parallel;
        private final int passageChars;

        public Options(int parallel, int passageChars) {
            if (parallel < 1 || passageChars < 1) {
                throw new IllegalArgumentException("at least one call at a time and one character of passage");
            }
            this.parallel = parallel;
            this.passageChars = passageChars;
        }

        public static Options defaults() {
            return new Options(DEFAULT_PARALLEL, DEFAULT_PASSAGE_CHARS);
        }

        public int getParallel() {
            return parallel;
        }

        public int getPassageChars() {
            return passageChars;
        }
    }

    /** The role: its own slot under Sources, a fixed schema, thinking off. */
    public static final ModelRole ROLE = ModelRole.builder("source-rating",
                    WritingSlots.PHASE_RESEARCH_SOURCES)
            .purpose("component 16: describes a source group (kind, jurisdiction, date, self-interest) from address, "
                    + "title, host and first passage; filters nothing")
            .promptFromCaller()
            .output(RoleOutput.schema(schema(), "anbieter, grund, quellenart, rechtsraum, eigeninteresse, datum"))
            .thinking(ThinkingMode.OFF)
            .build();

    static final double TEMPERATURE = 0.0;
    /** Answer budget: publisher, one sentence of reason, four short fields. */
    static final int ANSWER_TOKENS = 400;
    /** Added answer budget for the profile against the fence: the subject, the relation and the parts outside. */
    static final int FENCE_ANSWER_TOKENS = 400;
    private static final int MISSION_CHARS = 2000;
    /** Calls per group: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;
    private static final int TITLE_CHARS = 300;

    private final MainModelChat chat;
    private final KnownDomains known;
    private final boolean german;
    private final Options options;
    private final Clock clock;
    private final Consumer<String> log;
    private final Fence fence;

    /**
     * @param chat     the transport of the slot ({@link #ROLE})
     * @param known    the suggestions; {@link KnownDomains#none()} for none
     * @param language the working language ({@code "en"} for English prompts; anything else reads German)
     * @param options  the defaults when {@code null}
     * @param log      progress and errors; may be {@code null}
     */
    public SourceRater(MainModelChat chat, KnownDomains known, String language, Options options, Consumer<String> log) {
        this(chat, known, language, options, log, Clock.systemUTC(), null);
    }

    /**
     * The source profile against the Weidezaun: every source on its own, with the fence and the source's headings.
     *
     * @param fence the book's Weidezaun; {@code null} for the rating per host without it
     */
    public SourceRater(MainModelChat chat, KnownDomains known, String language, Options options, Consumer<String> log,
                       Fence fence) {
        this(chat, known, language, options, log, Clock.systemUTC(), fence);
    }

    SourceRater(MainModelChat chat, KnownDomains known, String language, Options options, Consumer<String> log,
                Clock clock) {
        this(chat, known, language, options, log, clock, null);
    }

    SourceRater(MainModelChat chat, KnownDomains known, String language, Options options, Consumer<String> log,
                Clock clock, Fence fence) {
        if (chat == null) {
            throw new IllegalArgumentException("the source rating needs a chat");
        }
        this.chat = chat;
        this.known = known == null ? KnownDomains.none() : known;
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.options = options == null ? Options.defaults() : options;
        this.log = log;
        this.clock = clock;
        this.fence = fence;
    }

    /** The name of a piece in the host's job list: the source's title, else its address, else its id. */
    private static String titleOf(SourceSample sample) {
        String title = sample.getTitle() == null ? "" : sample.getTitle().trim();
        if (!title.isEmpty()) {
            return title;
        }
        String address = sample.getAddress() == null ? "" : sample.getAddress().trim();
        return address.isEmpty() ? sample.getSourceId() : address;
    }

    /** One source: at most {@link #ATTEMPTS} calls. */
    public SourceRating rate(SourceSample sample) {
        KnownDomains.Entry suggestion = known.suggest(sample.getHost().isEmpty() ? sample.getAddress()
                : sample.getHost());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(sample, suggestion, options.getPassageChars(), german, fence)));
        boolean withFence = fence != null;
        int answerTokens = ANSWER_TOKENS + (withFence ? FENCE_ANSWER_TOKENS : 0);
        List<String> answers = new ArrayList<String>();
        String problem = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, answerTokens,
                        schema(withFence));
            } catch (RuntimeException failed) {
                result = MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
            }
            if (!result.isOk()) {
                answers.add("");
                problem = "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue; // the same request once more
            }
            String text = result.getText() == null ? "" : result.getText();
            answers.add(text);
            Parsed parsed = parse(text, withFence);
            if (parsed.problem.isEmpty()) {
                String date = parsed.date.isEmpty() ? dateOf(sample.getAddress()) : parsed.date;
                return new SourceRating(sample.getSourceId(), sample.getAddress(), sample.getHost(), sample.getTitle(),
                        parsed.kind, parsed.jurisdiction, date, parsed.selfInterest, parsed.publisher,
                        parsed.reason, suggestion, chat.modelName(), now(), SourceRating.Status.OK, "",
                        sample.getSourceId(), answers, parsed.fit);
            }
            problem = "invalid answer: " + parsed.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + parsed.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        log("source rating failed for " + sample.getHost() + ": " + problem);
        return failed(sample, suggestion, problem, answers);
    }

    /**
     * Every source, one call per group: the sources of one host share the rating of one source of the group, the first
     * in the given order that has text (else the first). With a fence every source is a group of its own: its relation
     * to the fence belongs to the document, not to the host. A host with a valid rating in {@code existing} is not asked
     * again, its rating is copied. Groups are rated {@link Options#getParallel()} at a time.
     *
     * @param existing earlier ratings by host (only {@link SourceRating.Status#OK} ones are reused); may be empty
     * @return one rating per source, in the given order
     */
    public List<SourceRating> rateAll(List<SourceSample> sources, Map<String, SourceRating> existing) {
        return rateAll(sources, existing, null);
    }

    /**
     * As {@link #rateAll(List, Map)}; {@code onEach} hears of every group as soon as it is rated (or failed), from the
     * thread that rated it, so a host can show the progress per source ({@code null}: nobody). A group taken from
     * {@code existing} counts at once.
     */
    public List<SourceRating> rateAll(List<SourceSample> sources, Map<String, SourceRating> existing,
                                      Runnable onEach) {
        return rateAll(sources, existing, onEach, Pieces.parallel(options.getParallel()));
    }

    /**
     * As {@link #rateAll(List, Map, Runnable)}; {@code pieces} runs the group ratings, one piece per group named by
     * the title of its first source, so a host can run each as a job of its own under the run's job. The stage is
     * {@link #STAGE}.
     */
    public List<SourceRating> rateAll(List<SourceSample> sources, Map<String, SourceRating> existing,
                                      Runnable onEach, Pieces pieces) {
        return rateAll(sources, existing, onEach, pieces, null);
    }

    /**
     * As {@link #rateAll(List, Map, Runnable, Pieces)}; {@code rated} gets every rating the model made, as soon as
     * it is made (not those taken from {@code existing}), so a host can keep it ({@code null}: nobody).
     */
    public List<SourceRating> rateAll(List<SourceSample> sources, Map<String, SourceRating> existing,
                                      Runnable onEach, Pieces pieces, final Consumer<SourceRating> rated) {
        final Runnable each = onEach == null ? new Runnable() {
            @Override
            public void run() {
            }
        } : onEach;
        final Map<String, List<SourceSample>> groups = new LinkedHashMap<String, List<SourceSample>>();
        for (SourceSample source : sources) {
            String group = groupOf(source);
            List<SourceSample> members = groups.get(group);
            if (members == null) {
                members = new ArrayList<SourceSample>();
                groups.put(group, members);
            }
            members.add(source);
        }
        final Map<String, SourceRating> byGroup = new LinkedHashMap<String, SourceRating>();
        List<String> ask = new ArrayList<String>();
        for (Map.Entry<String, List<SourceSample>> group : groups.entrySet()) {
            SourceRating earlier = existing == null ? null : existing.get(group.getKey());
            if (earlier != null && earlier.isOk()) {
                byGroup.put(group.getKey(), earlier);
                each.run();
            } else {
                ask.add(group.getKey());
            }
        }
        if (!ask.isEmpty()) {
            final AtomicInteger failuresInRow = new AtomicInteger();
            final AtomicReference<String> lastFailure = new AtomicReference<String>("");
            Pieces run = pieces == null ? Pieces.parallel(options.getParallel()) : pieces;
            List<SourceRating> answers = run.run(STAGE, ask, group -> titleOf(sampleOf(groups.get(group))), group -> {
                SourceSample first = sampleOf(groups.get(group));
                if (failuresInRow.get() >= Options.STOP_AFTER_FAILURES) {
                    each.run();
                    return failed(first, known.suggest(first.getHost()), "not asked: the model failed "
                            + Options.STOP_AFTER_FAILURES + " groups in a row; last: " + lastFailure.get(),
                            Collections.<String>emptyList());
                }
                SourceRating rating = rate(first);
                if (rating.isOk() && rated != null) {
                    rated.accept(rating);
                }
                if (rating.isOk()) {
                    failuresInRow.set(0);
                } else {
                    lastFailure.set(rating.getProblem());
                    failuresInRow.incrementAndGet();
                }
                each.run();
                return rating;
            });
            for (int i = 0; i < ask.size(); i++) {
                byGroup.put(ask.get(i), answers.get(i));
            }
        }
        List<SourceRating> out = new ArrayList<SourceRating>(sources.size());
        int failed = 0;
        for (SourceSample source : sources) {
            String group = groupOf(source);
            SourceRating rating = byGroup.get(group);
            SourceRating own = rating.getSourceId().equals(source.getSourceId()) ? rating
                    : rating.forSource(source, dateOf(source.getAddress()));
            out.add(own);
            if (!own.isOk()) {
                failed++;
            }
        }
        log("source rating: " + sources.size() + " sources in " + groups.size() + " groups, " + ask.size()
                + " asked, " + failed + " sources without a rating");
        return out;
    }

    /** The group a source shares its rating with: its host, or itself when the rating is asked with a fence. */
    /** The key of {@code existing} for {@code source}: with a fence every source is its own group. */
    public String groupKey(SourceSample source) {
        return groupOf(source);
    }

    /**
     * The key a {@link ProfileMemory} keeps the profile of {@code source} under: a fingerprint of the sample (address,
     * title, first passage, headings), the fence, the language and the passage length, so a changed page or fence asks
     * the model again.
     */
    public String memoryKey(SourceSample source) {
        StringBuilder text = new StringBuilder("profil-1\n");
        text.append(german ? "de" : "en").append('\n').append(options.getPassageChars()).append('\n');
        text.append(fence == null ? "" : fence.basis()).append('\n');
        text.append(source.getSourceId()).append('\n').append(source.getAddress()).append('\n');
        text.append(source.getTitle()).append('\n').append(source.getFirstPassage()).append('\n');
        for (String heading : source.getHeadings()) {
            text.append(heading).append('\n');
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String groupOf(SourceSample source) {
        if (fence != null) {
            return "quelle:" + source.getSourceId();
        }
        return source.getHost().isEmpty() ? "adresse:" + source.getAddress() : source.getHost();
    }

    /** The source the model reads for a group: the first one with text, else the first. */
    static SourceSample sampleOf(List<SourceSample> members) {
        for (SourceSample member : members) {
            if (!member.getFirstPassage().trim().isEmpty()) {
                return member;
            }
        }
        return members.get(0);
    }

    private SourceRating failed(SourceSample sample, KnownDomains.Entry suggestion, String problem,
                                List<String> answers) {
        return new SourceRating(sample.getSourceId(), sample.getAddress(), sample.getHost(), sample.getTitle(), null,
                null, "", null, "", "", suggestion, chat.modelName(), now(), SourceRating.Status.FEHLER, problem,
                sample.getSourceId(), answers);
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private void log(String line) {
        if (log != null) {
            log.accept(line);
        }
    }

    static final String SYSTEM_DE = "Du beschreibst Quellen für ein Sachbuch. Du bekommst Adresse, Domain, Titel und "
            + "den Anfang einer Webseite oder eines Dokuments und ordnest die Quelle ein: wer sie herausgibt, welche "
            + "Art von Quelle sie ist, für welchen Rechtsraum sie spricht und ob der Herausgeber ein eigenes Interesse "
            + "am Thema hat. Du filterst nichts und beurteilst nicht, ob die Quelle zum Buch passt. Antworte nur mit "
            + "JSON.";

    static final String SYSTEM_EN = "You describe sources for a non-fiction book. You get the address, domain, title "
            + "and the beginning of a web page or document and classify the source: who publishes it, what kind of "
            + "source it is, for which jurisdiction it speaks and whether the publisher has an interest of its own in "
            + "the subject. You filter nothing and do not judge whether the source fits the book. Answer only with "
            + "JSON.";

    static String prompt(SourceSample sample, KnownDomains.Entry suggestion, int passageChars, boolean german) {
        return prompt(sample, suggestion, passageChars, german, null);
    }

    static String prompt(SourceSample sample, KnownDomains.Entry suggestion, int passageChars, boolean german,
                         Fence fence) {
        String passage = cut(sample.getFirstPassage().replaceAll("\\s+", " ").trim(), passageChars);
        String title = cut(sample.getTitle().replaceAll("\\s+", " ").trim(), TITLE_CHARS);
        StringBuilder text = new StringBuilder();
        if (german) {
            text.append("Adresse: ").append(sample.getAddress()).append('\n')
                    .append("Domain: ").append(sample.getHost()).append('\n')
                    .append("Titel: ").append(title.isEmpty() ? "(keiner)" : title).append('\n')
                    .append("Anfang des Textes:\n").append(passage.isEmpty() ? "(kein Text)" : passage).append("\n\n");
            if (suggestion != null) {
                text.append("Hinweis aus der Liste bekannter Anbieter: ").append(suggestion.getDomain()).append(" = ")
                        .append(suggestion.getKind()).append(", ").append(suggestion.getJurisdiction())
                        .append(suggestion.getNote().isEmpty() ? "" : " (" + suggestion.getNote() + ")")
                        .append(". Übernimm das nur, wenn die Seite dazu passt.\n\n");
            }
            text.append("Quellenarten:\n"
                    + "- GESETZ_NORM: Text eines Gesetzes, einer Verordnung, einer Richtlinie oder einer technischen "
                    + "Norm, oder eine amtliche Sammlung solcher Texte.\n"
                    + "- BEHOERDE_UNFALLVERSICHERUNG: Behörde, Ministerium, Aufsichtsamt oder andere öffentliche "
                    + "Stelle, auch eine gesetzliche Versicherung, auch deren Zeitschriften und Portale.\n"
                    + "- WISSENSCHAFT: Hochschule, Forschungsinstitut, wissenschaftliche Zeitschrift oder "
                    + "wissenschaftlicher Verlag.\n"
                    + "- FACHPRESSE: Zeitung, Zeitschrift, Fachverlag oder Nachrichtenportal mit Redaktion.\n"
                    + "- KANZLEI_BERATUNG: Anwaltskanzlei, Beratungsfirma oder ein Ratgeberportal.\n"
                    + "- HERSTELLER_HANDEL: Hersteller, Händler, Software- oder IT-Firma, Prüfstelle oder anderer "
                    + "Anbieter, der Produkte oder technische Leistungen zum Thema verkauft, auch dessen Blog oder "
                    + "Ratgeber.\n"
                    + "- VERBAND: Branchenverband, Berufsverband, Kammer oder Gewerkschaft.\n"
                    + "- NACHSCHLAGEWERK: Lexikon, Wiki, Glossar oder nicht amtliche Sammlung von Gesetzestexten.\n"
                    + "- SONSTIGE: alles andere, etwa soziale Netzwerke, Foren, private Blogs oder Seiten, deren "
                    + "Herausgeber nicht erkennbar ist.\n\n"
                    + "Rechtsraum: für wessen Recht und Behörden die Quelle spricht: DE, AT, CH, EU (Organe und Recht "
                    + "der Europäischen Union), ANDERE (ein anderes Land) oder OHNE (die Quelle spricht für keinen "
                    + "Rechtsraum, etwa ein Hersteller ohne Rechtsaussagen oder eine internationale Fachzeitschrift).\n"
                    + "Eigeninteresse: JA, wenn der Herausgeber Produkte oder Leistungen verkauft, um die es im Text "
                    + "geht; NEIN bei Behörden, Unfallversicherungen, Hochschulen, Nachschlagewerken und unabhängiger "
                    + "Presse; UNKLAR, wenn es sich nicht erkennen lässt.\n"
                    + "Datum: das Erscheinungs- oder Stand-Datum, wenn es in Text, Titel oder Adresse steht, als JJJJ, "
                    + "JJJJ-MM oder JJJJ-MM-TT; sonst leer.\n\n"
                    + "Schreibe zuerst den Herausgeber und in einem Satz den Grund, dann die Einordnung. Antworte als "
                    + "JSON mit den Feldern \"anbieter\", \"grund\", \"quellenart\", \"rechtsraum\", "
                    + "\"eigeninteresse\" und \"datum\"");
            if (fence == null) {
                return text.append('.').toString();
            }
            text.append(", dazu \"gegenstand\", \"zaun\" und \"aussenTeile\".\n\n")
                    .append("Weidezaun des Buchs:\n").append(fence.describe(true, MISSION_CHARS))
                    .append(headings(sample, passageChars, true))
                    .append("gegenstand: in einem Satz, wovon die Quelle handelt.\n"
                    + "zaun: INNEN, wenn die ganze Quelle innerhalb des Weidezauns liegt; GEMISCHT, wenn Teile "
                    + "innerhalb und Teile außerhalb liegen (etwa ein Dokument zu mehreren Gegenständen, von denen "
                    + "nur einer zum Buch gehört); AUSSEN, wenn die Quelle ganz außerhalb liegt; UNKLAR, wenn "
                    + "sich das aus Titel, Überschriften und Anfang nicht sagen lässt. Im Zweifel UNKLAR, nie "
                    + "AUSSEN.\n"
                    + "aussenTeile: nur bei GEMISCHT die Überschriften der Teile außerhalb, wörtlich aus der Liste "
                    + "oben; sonst eine leere Liste.");
            return text.toString();
        }
        text.append("Address: ").append(sample.getAddress()).append('\n')
                .append("Domain: ").append(sample.getHost()).append('\n')
                .append("Title: ").append(title.isEmpty() ? "(none)" : title).append('\n')
                .append("Beginning of the text:\n").append(passage.isEmpty() ? "(no text)" : passage).append("\n\n");
        if (suggestion != null) {
            text.append("Hint from the list of known publishers: ").append(suggestion.getDomain()).append(" = ")
                    .append(suggestion.getKind()).append(", ").append(suggestion.getJurisdiction())
                    .append(suggestion.getNote().isEmpty() ? "" : " (" + suggestion.getNote() + ")")
                    .append(". Take it over only when the page fits it.\n\n");
        }
        text.append("Kinds of source (quellenart):\n"
                + "- GESETZ_NORM: text of a law, regulation, directive or technical standard, or an official "
                + "collection of such texts.\n"
                + "- BEHOERDE_UNFALLVERSICHERUNG: authority, ministry, inspectorate or other public body, also a "
                + "statutory insurer, also their journals and portals.\n"
                + "- WISSENSCHAFT: university, research institute, scientific journal or scientific publisher.\n"
                + "- FACHPRESSE: newspaper, magazine, trade publisher or news portal with an editorial office.\n"
                + "- KANZLEI_BERATUNG: law firm, consultancy or an advice portal.\n"
                + "- HERSTELLER_HANDEL: manufacturer, dealer, software or IT company, testing body or other provider "
                + "that sells products or technical services on the subject, also its blog or guide.\n"
                + "- VERBAND: industry association, professional association, chamber or trade union.\n"
                + "- NACHSCHLAGEWERK: encyclopedia, wiki, glossary or unofficial collection of legal texts.\n"
                + "- SONSTIGE: everything else, such as social networks, forums, private blogs or pages whose "
                + "publisher cannot be seen.\n\n"
                + "Jurisdiction (rechtsraum): for whose law and authorities the source speaks: DE, AT, CH, EU "
                + "(institutions and law of the European Union), ANDERE (another country) or OHNE (no jurisdiction, "
                + "such as a manufacturer without legal statements or an international journal).\n"
                + "Self-interest (eigeninteresse): JA when the publisher sells products or services the text is "
                + "about; NEIN for authorities, accident insurers, universities, reference works and independent "
                + "press; UNKLAR when it cannot be seen.\n"
                + "Date (datum): the date of publication or of the state of the text when text, title or address "
                + "show it, as YYYY, YYYY-MM or YYYY-MM-DD; else empty.\n\n"
                + "First write the publisher and the reason in one sentence, then the classification. Answer as JSON "
                + "with the fields \"anbieter\", \"grund\", \"quellenart\", \"rechtsraum\", \"eigeninteresse\" and "
                + "\"datum\"");
        if (fence == null) {
            return text.append('.').toString();
        }
        text.append(", and \"gegenstand\", \"zaun\" and \"aussenTeile\".\n\n")
                .append("Weidezaun (fence) of the book:\n").append(fence.describe(false, MISSION_CHARS))
                .append(headings(sample, passageChars, false))
                .append("gegenstand: in one sentence, what the source is about.\n"
                + "zaun: INNEN when the whole source lies inside the fence; GEMISCHT when parts lie inside and parts "
                + "outside (such as a document on several subjects of which only one belongs to the book); AUSSEN "
                + "when the source lies wholly outside; UNKLAR when title, headings and beginning do not tell. When in "
                + "doubt UNKLAR, never AUSSEN.\n"
                + "aussenTeile: only for GEMISCHT the headings of the parts outside, verbatim from the list above; "
                + "else an empty list.");
        return text.toString();
    }

    /** The headings of the source as the prompt lists them, within {@code chars} characters; empty without any. */
    static String headings(SourceSample sample, int chars, boolean german) {
        List<String> all = sample.getHeadings();
        if (all.isEmpty()) {
            return german ? "Überschriften der Quelle: (keine)\n\n" : "Headings of the source: (none)\n\n";
        }
        StringBuilder list = new StringBuilder(german ? "Überschriften der Quelle:\n" : "Headings of the source:\n");
        int used = 0;
        int shown = 0;
        for (String heading : all) {
            if (shown > 0 && used + heading.length() > chars) {
                break;
            }
            list.append("- ").append(heading).append('\n');
            used += heading.length();
            shown++;
        }
        if (shown < all.size()) {
            list.append(german ? "- … und " + (all.size() - shown) + " weitere\n"
                    : "- … and " + (all.size() - shown) + " more\n");
        }
        return list.append('\n').toString();
    }

    /** The publisher and the reason first, then the four fields; all required, nothing else allowed. */
    static String schema() {
        return schema(false);
    }

    /** With a fence: also the subject, the relation to the fence and the parts outside. */
    static String schema(boolean withFence) {
        JsonObject properties = new JsonObject();
        properties.add("anbieter", string());
        properties.add("grund", string());
        properties.add("quellenart", enumOf(SourceRating.Kind.values()));
        properties.add("rechtsraum", enumOf(SourceRating.Jurisdiction.values()));
        properties.add("eigeninteresse", enumOf(SourceRating.SelfInterest.values()));
        properties.add("datum", string());
        JsonArray required = new JsonArray();
        for (String field : new String[]{"anbieter", "grund", "quellenart", "rechtsraum", "eigeninteresse", "datum"}) {
            required.add(field);
        }
        if (withFence) {
            properties.add("gegenstand", string());
            properties.add("zaun", enumOf(SourceRating.Relation.values()));
            JsonObject parts = new JsonObject();
            parts.addProperty("type", "array");
            parts.add("items", string());
            properties.add("aussenTeile", parts);
            required.add("gegenstand");
            required.add("zaun");
            required.add("aussenTeile");
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    private static JsonObject string() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        return o;
    }

    private static JsonObject enumOf(Enum<?>[] values) {
        JsonObject o = string();
        JsonArray names = new JsonArray();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        o.add("enum", names);
        return o;
    }

    /** A rating read from an answer, or why the answer holds none. */
    static final class Parsed {
        SourceRating.Kind kind;
        SourceRating.Jurisdiction jurisdiction;
        SourceRating.SelfInterest selfInterest;
        String date = "";
        String publisher = "";
        String reason = "";
        String problem = "";
        SourceRating.Fit fit = SourceRating.Fit.NONE;
    }

    static Parsed parse(String answer) {
        return parse(answer, false);
    }

    /** With {@code withFence} the answer must also hold the relation to the fence. */
    static Parsed parse(String answer, boolean withFence) {
        Parsed parsed = new Parsed();
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(answer == null ? "" : answer.trim());
            if (!element.isJsonObject()) {
                parsed.problem = "the answer is no JSON object";
                return parsed;
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            parsed.problem = "the answer is no JSON";
            return parsed;
        }
        parsed.kind = value(json, "quellenart", SourceRating.Kind.class);
        parsed.jurisdiction = value(json, "rechtsraum", SourceRating.Jurisdiction.class);
        parsed.selfInterest = value(json, "eigeninteresse", SourceRating.SelfInterest.class);
        List<String> missing = new ArrayList<String>();
        if (parsed.kind == null) {
            missing.add("\"quellenart\"");
        }
        if (parsed.jurisdiction == null) {
            missing.add("\"rechtsraum\"");
        }
        if (parsed.selfInterest == null) {
            missing.add("\"eigeninteresse\"");
        }
        if (!missing.isEmpty()) {
            parsed.problem = String.join(", ", missing) + " missing or not one of the allowed values";
            return parsed;
        }
        // A transport may fall back to plain completion without the schema: publisher and reason must be there as
        // text (the publisher may be empty when the page names none), else the answer takes the repair path.
        if (!isText(json.get("anbieter")) || !isText(json.get("grund")) || text(json, "grund").isEmpty()) {
            parsed.problem = "\"anbieter\" and \"grund\" must be texts, \"grund\" not empty";
            return parsed;
        }
        parsed.publisher = cut(text(json, "anbieter"), 200);
        parsed.reason = cut(text(json, "grund"), 400);
        String date = text(json, "datum");
        parsed.date = date.matches("\\d{4}(-\\d{2}(-\\d{2})?)?") ? date : "";
        if (withFence) {
            SourceRating.Relation relation = value(json, "zaun", SourceRating.Relation.class);
            if (relation == null) {
                parsed.problem = "\"zaun\" missing or not one of INNEN, GEMISCHT, AUSSEN, UNKLAR";
                return parsed;
            }
            List<String> outside = new ArrayList<String>();
            JsonElement parts = json.get("aussenTeile");
            if (relation == SourceRating.Relation.GEMISCHT && parts != null && parts.isJsonArray()) {
                for (JsonElement part : parts.getAsJsonArray()) {
                    if (isText(part) && !part.getAsString().trim().isEmpty()) {
                        outside.add(cut(part.getAsString().replaceAll("\\s+", " ").trim(), 300));
                    }
                }
            }
            parsed.fit = new SourceRating.Fit(cut(text(json, "gegenstand"), 400), relation, outside);
        }
        return parsed;
    }

    private static boolean isText(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static <E extends Enum<E>> E value(JsonObject json, String field, Class<E> type) {
        String word = text(json, field).toUpperCase(Locale.ROOT).replace("Ä", "AE").replace("Ö", "OE")
                .replace("Ü", "UE").replace("ß", "SS").replaceAll("[\\s-]+", "_");
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(word)) {
                return constant;
            }
        }
        return null;
    }

    private static final Pattern DAY_IN_ADDRESS = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})[-/.](0[1-9]|1[0-2])[-/.](0[1-9]|[12]\\d|3[01])(?!\\d)");
    private static final Pattern MONTH_IN_ADDRESS = Pattern.compile(
            "/((?:19|20)\\d{2})/(0[1-9]|1[0-2])/");

    /**
     * The date an address shows by itself ({@code 2023-05-17}, {@code 2023/05/17} or the path {@code /2023/05/}), as
     * {@code JJJJ-MM-TT} or {@code JJJJ-MM}; empty when it shows none. Years alone are not taken: addresses carry
     * norm and document numbers.
     */
    static String dateOf(String address) {
        String a = address == null ? "" : address;
        Matcher day = DAY_IN_ADDRESS.matcher(a);
        if (day.find()) {
            return day.group(1) + "-" + day.group(2) + "-" + day.group(3);
        }
        Matcher month = MONTH_IN_ADDRESS.matcher(a);
        return month.find() ? month.group(1) + "-" + month.group(2) : "";
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().replaceAll("\\s+", " ").trim() : "";
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim() + " …";
    }
}
