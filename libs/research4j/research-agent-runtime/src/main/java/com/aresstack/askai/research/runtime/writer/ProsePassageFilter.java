package com.aresstack.askai.research.runtime.writer;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells prose passages from page boilerplate (live run 3, fix 2): navigation and footer text ("Vertriebsteam
 * kontaktieren Alle Produkte durchsuchen … Investor Relations …"), breadcrumbs and bare headings ("Analyse der
 * Implementierungskosten Anfängliche Investitionsanforderungen") are no evidence, because they carry no statement a
 * sentence could rest on. The same rule the Sources phase applies to a whole page (too little text to count) applies
 * here to one passage, with structural criteria only and no word lists:
 * <ol>
 * <li>at least {@link Criteria#getMinWords() min words} words; a word holds at least one letter, so "©", "·",
 * "2026" and "12555" do not count (live run 4: "© 2026 Oxeltech GmbH · Mahlsdorfer Str. 10, 12555 Berlin" made
 * ten tokens with five words);</li>
 * <li>at least {@link Criteria#getMinSentences() min sentences} finished sentences (a {@code .}, {@code !} or
 * {@code ?} after a letter, digit or closing bracket/quote, followed by white space or the end; the dot of
 * {@code dormakaba.com} does not count);</li>
 * <li>no run of more than {@link Criteria#getMaxUnpunctuatedRun() max run} words without any punctuation: menu
 * labels follow each other without commas or full stops, prose does not.</li>
 * </ol>
 * One more rule needs the whole page ({@link #captions}): on a page that is almost no prose (a shop or listing
 * page), a short sentence standing alone between non-prose passages is a caption, slogan or teaser, not a statement.
 * Stateless and deterministic; every bound is a setting.
 */
public final class ProsePassageFilter {

    /** A finished sentence: terminal punctuation after a word character or closer, then space or end. */
    private static final Pattern SENTENCE_END = Pattern.compile("[\\p{L}\\p{Nd}\\)\"“”»’'][.!?]+(?=\\s|$)");
    /** Any mark that interrupts a run of words in prose. */
    private static final Pattern PUNCTUATION = Pattern.compile("[.,;:!?()\\[\\]–—\"“”„«»]");
    /** A token that is a word: it holds a letter. */
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    /** The thresholds; immutable. */
    public static final class Criteria {
        /** Default minimum words: shorter passages are headings or labels (the live-run headings had 5 and 8). */
        public static final int DEFAULT_MIN_WORDS = 10;
        /** Default minimum finished sentences. */
        public static final int DEFAULT_MIN_SENTENCES = 1;
        /** Default longest punctuation-free run; the hexagon.com footer of live run 3 had more than 60 words. */
        public static final int DEFAULT_MAX_UNPUNCTUATED_RUN = 30;
        /**
         * Default most words of a caption. Live run 4: the calendar slogans of a shop page had 10 to 19 words; the
         * shortest lone passage with content on a page that was almost no prose had 26 (bmas.de, "Das
         * Arbeitsschutzgesetz regelt …"). The default lies between.
         */
        public static final int DEFAULT_CAPTION_MAX_WORDS = 22;
        /**
         * Default share of prose passages below which a page is a listing. Live run 4: the shop page of
         * mediencenter.bgrci.de had 27 prose passages of 996 (0.027); the next pages with a short lone passage had
         * 0.1 and 0.11 (bgetem.de, pbvi.de). The default lies between.
         */
        public static final double DEFAULT_CAPTION_PAGE_PROSE_SHARE = 0.05;
        /**
         * Default most promotion signals (call-to-action phrases, prices, subscription terms) a passage may carry;
         * one more and it is advertising or navigation (a passage of caption length needs only one). Live retest of #240: "JETZT TICKET SICHERN! Zurück
         * Vorheriger", "Bei Weihnachtseinkäufen ab dem 16", "Sie gehen kein Risiko ein: Jederzeit formlos zu sofort
         * kündbar" reached the outline as topics. 0 turns the rule off.
         */
        public static final int DEFAULT_MAX_PROMOTION_SIGNALS = 1;
        /** Default largest share of words (three letters or more) written in capitals; 1 turns the rule off. */
        public static final double DEFAULT_MAX_UPPERCASE_SHARE = 0.4;
        /** Default most links (URLs, "www") per word; "WWW: https://www" reached the outline. 1 turns it off. */
        public static final double DEFAULT_MAX_LINK_DENSITY = 0.05;
        /**
         * Default most site notices (cookie consent, FAQ headers, "War der Artikel hilfreich", "Nachfolgend finden
         * Sie eine Auswahl interner Fundstellen") a passage may carry. A cookie banner is long, finished prose with a
         * single notice, so one is enough. Live retest of #243: two cookie banners and an FAQ header became sections.
         * "Einwilligung", "zustimmen" or "ablehnen" alone are no notice: a book on workplace data protection discusses
         * consent; only the consent buttons ("Zustimmen Ablehnen", "Einwilligung verwalten") count.
         */
        public static final int DEFAULT_MAX_SITE_NOTICES = 0;

        private final int minWords;
        private final int minSentences;
        private final int maxUnpunctuatedRun;
        private final int captionMaxWords;
        private final double captionPageProseShare;
        private final int maxPromotionSignals;
        private final double maxUppercaseShare;
        private final double maxLinkDensity;
        private final int maxSiteNotices;

        public Criteria(int minWords, int minSentences, int maxUnpunctuatedRun) {
            this(minWords, minSentences, maxUnpunctuatedRun, DEFAULT_CAPTION_MAX_WORDS,
                    DEFAULT_CAPTION_PAGE_PROSE_SHARE);
        }

        /**
         * @param captionMaxWords       most words of a lone passage that counts as a caption on a listing page
         * @param captionPageProseShare a page with a smaller share of prose passages is a listing (0 = never)
         */
        public Criteria(int minWords, int minSentences, int maxUnpunctuatedRun, int captionMaxWords,
                        double captionPageProseShare) {
            this(minWords, minSentences, maxUnpunctuatedRun, captionMaxWords, captionPageProseShare,
                    DEFAULT_MAX_PROMOTION_SIGNALS, DEFAULT_MAX_UPPERCASE_SHARE, DEFAULT_MAX_LINK_DENSITY);
        }

        /**
         * @param maxPromotionSignals most call-to-action, price and subscription signals (0 = rule off)
         * @param maxUppercaseShare   largest share of words in capitals (1 = rule off)
         * @param maxLinkDensity      most links per word (1 = rule off)
         */
        public Criteria(int minWords, int minSentences, int maxUnpunctuatedRun, int captionMaxWords,
                        double captionPageProseShare, int maxPromotionSignals, double maxUppercaseShare,
                        double maxLinkDensity) {
            this(minWords, minSentences, maxUnpunctuatedRun, captionMaxWords, captionPageProseShare,
                    maxPromotionSignals, maxUppercaseShare, maxLinkDensity, DEFAULT_MAX_SITE_NOTICES);
        }

        /** @param maxSiteNotices most cookie, FAQ and site-navigation notices (a large value turns the rule off) */
        public Criteria(int minWords, int minSentences, int maxUnpunctuatedRun, int captionMaxWords,
                        double captionPageProseShare, int maxPromotionSignals, double maxUppercaseShare,
                        double maxLinkDensity, int maxSiteNotices) {
            if (maxSiteNotices < 0) {
                throw new IllegalArgumentException("site notices must not be negative");
            }
            this.maxSiteNotices = maxSiteNotices;
            if (maxPromotionSignals < 0 || maxUppercaseShare < 0 || maxUppercaseShare > 1 || maxLinkDensity < 0
                    || maxLinkDensity > 1) {
                throw new IllegalArgumentException("promotion criteria must not be negative and the shares must lie "
                        + "in 0..1");
            }
            this.maxPromotionSignals = maxPromotionSignals;
            this.maxUppercaseShare = maxUppercaseShare;
            this.maxLinkDensity = maxLinkDensity;
            if (minWords < 0 || minSentences < 0 || maxUnpunctuatedRun < 1 || captionMaxWords < 0
                    || captionPageProseShare < 0 || captionPageProseShare > 1) {
                throw new IllegalArgumentException("prose criteria must not be negative, the run must be >= 1 and "
                        + "the page share must lie in 0..1");
            }
            this.minWords = minWords;
            this.minSentences = minSentences;
            this.maxUnpunctuatedRun = maxUnpunctuatedRun;
            this.captionMaxWords = captionMaxWords;
            this.captionPageProseShare = captionPageProseShare;
        }

        public static Criteria defaults() {
            return new Criteria(DEFAULT_MIN_WORDS, DEFAULT_MIN_SENTENCES, DEFAULT_MAX_UNPUNCTUATED_RUN,
                    DEFAULT_CAPTION_MAX_WORDS, DEFAULT_CAPTION_PAGE_PROSE_SHARE, DEFAULT_MAX_PROMOTION_SIGNALS,
                    DEFAULT_MAX_UPPERCASE_SHARE, DEFAULT_MAX_LINK_DENSITY, DEFAULT_MAX_SITE_NOTICES);
        }

        public int getMaxSiteNotices() {
            return maxSiteNotices;
        }

        public int getMaxPromotionSignals() {
            return maxPromotionSignals;
        }

        public double getMaxUppercaseShare() {
            return maxUppercaseShare;
        }

        public double getMaxLinkDensity() {
            return maxLinkDensity;
        }

        public int getMinWords() {
            return minWords;
        }

        public int getMinSentences() {
            return minSentences;
        }

        public int getMaxUnpunctuatedRun() {
            return maxUnpunctuatedRun;
        }

        public int getCaptionMaxWords() {
            return captionMaxWords;
        }

        public double getCaptionPageProseShare() {
            return captionPageProseShare;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Criteria)) {
                return false;
            }
            Criteria c = (Criteria) o;
            return c.minWords == minWords && c.minSentences == minSentences
                    && c.maxUnpunctuatedRun == maxUnpunctuatedRun && c.captionMaxWords == captionMaxWords
                    && Double.compare(c.captionPageProseShare, captionPageProseShare) == 0
                    && c.maxPromotionSignals == maxPromotionSignals
                    && Double.compare(c.maxUppercaseShare, maxUppercaseShare) == 0
                    && Double.compare(c.maxLinkDensity, maxLinkDensity) == 0 && c.maxSiteNotices == maxSiteNotices;
        }

        @Override
        public int hashCode() {
            return (((minWords * 31 + minSentences) * 31 + maxUnpunctuatedRun) * 31 + captionMaxWords) * 31
                    + Double.valueOf(captionPageProseShare).hashCode() + 17 * maxPromotionSignals
                    + Double.valueOf(maxUppercaseShare).hashCode() + Double.valueOf(maxLinkDensity).hashCode() + 13 * maxSiteNotices;
        }

        @Override
        public String toString() {
            return "words>=" + minWords + ", sentences>=" + minSentences + ", run<=" + maxUnpunctuatedRun
                    + ", caption<=" + captionMaxWords + " on pages with prose<" + captionPageProseShare
                    + ", promotion<=" + maxPromotionSignals + ", capitals<=" + maxUppercaseShare + ", links<="
                    + maxLinkDensity + ", notices<=" + maxSiteNotices;
        }
    }

    private final Criteria criteria;

    public ProsePassageFilter(Criteria criteria) {
        this.criteria = criteria == null ? Criteria.defaults() : criteria;
    }

    public Criteria getCriteria() {
        return criteria;
    }

    /** The rule a passage breaks ({@link #rule}); {@link #NONE} for prose. */
    public enum Rule {
        NONE,
        /** Fewer words than {@link Criteria#getMinWords()}: a heading or a label. */
        WORDS,
        /** Fewer finished sentences than {@link Criteria#getMinSentences()}. */
        SENTENCES,
        /** A run of words without punctuation longer than {@link Criteria#getMaxUnpunctuatedRun()}: a menu. */
        RUN,
        /** Cookie banners, FAQ headers, picture credits, licences, imprint and order hints. */
        SITE_NOTICES,
        /** Call-to-action phrases, prices, coupons and subscription terms. */
        PROMOTION,
        /** Too many words in capitals: an advertising line. */
        CAPITALS,
        /** Too many links: a link list. */
        LINKS
    }

    /** A rule with the reason as {@link #reason} gives it. */
    private static final class Finding {
        static final Finding PROSE = new Finding(Rule.NONE, "");
        final Rule rule;
        final String reason;

        Finding(Rule rule, String reason) {
            this.rule = rule;
            this.reason = reason;
        }
    }

    /** Whether the passage is prose that can carry a statement. */
    public boolean isProse(String text) {
        return reason(text).isEmpty();
    }

    /**
     * Why the passage is boilerplate ("5 words < 10", ...); empty when it is prose. A soft hyphen (U+00AD) is removed,
     * not read as a blank: "Arbeits\u00ADschutz" is one word, so a hyphenated heading does not count its words twice.
     */
    public String reason(String text) {
        return finding(text, false).reason;
    }

    /** Which rule the passage breaks, by the same check as {@link #reason}; {@link Rule#NONE} when it is prose. */
    public Rule rule(String text) {
        return finding(text, false).rule;
    }

    /**
     * The rule as {@link #rule(String)}, except that with {@code spareProseWords} a single site notice that is also a
     * word of prose on the matter ({@link #PROSE_WORD_NOTICE}: cookies, FAQ, privacy settings, withdrawn consent,
     * home page, imprint, a licence) breaks no rule; the other rules still apply. The page-noise check lets its model
     * judge such a passage: its acceptance of 22.11.2026 found two passages of content that the notice rule took for
     * leftovers on one such word. The writer keeps the strict rule.
     */
    public Rule rule(String text, boolean spareProseWords) {
        return finding(text, spareProseWords).rule;
    }

    /** The reason as {@link #reason(String)}, under the same exception as {@link #rule(String, boolean)}. */
    public String reason(String text, boolean spareProseWords) {
        return finding(text, spareProseWords).reason;
    }

    private Finding finding(String text, boolean spareProseWords) {
        String t = text == null ? "" : text.replace("\u00AD", "").trim();
        String[] words = t.isEmpty() ? new String[0] : t.split("\\s+");
        int count = words(t);
        if (count < criteria.minWords) {
            return new Finding(Rule.WORDS, count + " words < " + criteria.minWords);
        }
        int sentences = 0;
        Matcher end = SENTENCE_END.matcher(t);
        while (end.find()) {
            sentences++;
        }
        if (sentences < criteria.minSentences) {
            return new Finding(Rule.SENTENCES, sentences + " finished sentences < " + criteria.minSentences);
        }
        int run = 0;
        int longest = 0;
        for (String word : words) {
            String bare = PUNCTUATION.matcher(word).replaceAll("");
            boolean punctuated = bare.length() != word.length();
            if (!bare.isEmpty()) {
                run++;
                longest = Math.max(longest, run);
            }
            if (punctuated) {
                run = 0;
            }
        }
        if (longest > criteria.maxUnpunctuatedRun) {
            return new Finding(Rule.RUN, longest + " words without punctuation > " + criteria.maxUnpunctuatedRun);
        }
        return promotion(t, words, count, spareProseWords);
    }

    /** Call-to-action, navigation, price and subscription phrases (German and English). */
    private static final Pattern PROMOTION = Pattern.compile("(?iu)\\b(?:jetzt\\s+(?:kaufen|bestellen|sichern|"
            + "anmelden|buchen|testen|registrieren|entdecken)|kostenlos\\s+(?:bestellen|testen|anmelden|herunterladen)"
            + "|weiterlesen|mehr\\s+erfahren|mehr\\s+lesen|zum\\s+shop|warenkorb|newsletter|abonnieren"
            + "|jederzeit\\s+(?:formlos\\s+)?(?:zu\\s+)?(?:sofort\\s+)?kündbar|kündbar|gratis|\\w*gutschein\\w*|\\w*rabatt\\w*|jetzt\\s+profitieren|geschenk\\s+erhalten"
            + "|produkt\\s+kaufen|(?:schützen|sichern|optimieren|steigern)\\s+sie\\s+ihre|unsere\\s+(?:zertifizierten\\s+)?lösungen"
            + "|zertifizierten\\s+\\S+-?lösungen|einlösbar|versandkostenfrei"
            + "|alle\\s+akzeptieren|war\\s+der\\s+artikel\\s+hilfreich|vorheriger|katalog\\s+kostenlos"
            + "|weihnachtseinkäufe\\w*|buy\\s+now|sign\\s+up|subscribe|read\\s+more|learn\\s+more|add\\s+to\\s+cart"
            + "|free\\s+trial|accept\\s+all|shop\\s+now|contact\\s+us|request\\s+a\\s+quote"
            + "|nehmen\\s+sie\\s+(?:jetzt\\s+)?kontakt|kontaktieren\\s+sie\\s+uns|ihr\\s+(?:spezialist|partner|"
            + "ansprechpartner|experte)|unsere\\s+empfehlungen|als\\s+erfahrenes\\s+unternehmen|lassen\\s+sie\\s+uns"
            + "|wir\\s+beraten\\s+sie|fordern\\s+sie|jetzt\\s+anfragen|angebot\\s+anfordern|per\\s+e-?mail\\s+teilen"
            + "|teilen|quicklinks|filtern\\s+sie|durchsuchen\\s+sie|unser\\s+team)\\b|®"
            + "|\\d+(?:[.,]\\d+)?\\s?(?:€|eur\\b|euro\\b|chf\\b|\\$|usd\\b)|(?:€|\\$)\\s?\\d"
            + "|-\\s?\\d+\\s?%|\\b(?:pro|je)\\s+monat\\b|/\\s?monat\\b|\\bper\\s+month\\b");
    private static final Pattern SITE_NOTICE = Pattern.compile("(?iu)\\b(?:(?:verwendet|verwenden|nutzt|nutzen|"
            + "setzt|setzen|benutzt|benutzen)\\s+(?:\\S+\\s+){0,3}cookies|cookie-?(?:einstellungen|richtlinie|hinweis|banner)"
            + "|(?:we|this\\s+(?:web)?site)\\s+uses?\\s+cookies|cookie\\s+(?:settings|policy)"
            + "|häufig\\s+gestellte\\s+fragen|frequently\\s+asked\\s+questions|faq"
            + "|war\\s+(?:der|dieser|diese)\\s+(?:artikel|beitrag|seite)\\s+hilfreich|was\\s+this\\s+(?:article|page)\\s+helpful"
            + "|nachfolgend\\s+finden\\s+sie\\s+eine\\s+auswahl|interne[nr]?\\s+fundstellen|zum\\s+inhalt\\s+springen"
            + "|skip\\s+to\\s+(?:main\\s+)?content|cookies?|datenschutzeinstellungen|privatsphäre-?einstellungen"
            + "|(?:zustimmen|akzeptieren|annehmen)\\s*(?:und|oder|/|\\|)?\\s*ablehnen|einwilligung\\s+(?:verwalten|widerrufen|"
            + "anpassen)|alle\\s+akzeptieren|consent\\s+(?:settings|preferences)|privacy\\s+settings|seite\\s+drucken|print\\s+this\\s+page"
            + "|startseite|impressum|wie\\s+gefällt\\s+ihnen|rate\\s+this|ausfertigungsdatum"
            + "|istockphoto|shutterstock|adobe\\s*stock|getty\\s+images|bildnachweis|bildquelle|fotonachweis"
            + "|creative\\s+commons|cc[\\s-]by(?:-\\S+)?|licensed\\s+under|lizenziert\\s+unter|unter\\s+der\\s+lizenz"
            + "|(?:für\\s+)?bestellungen\\s+bis|lieferung\\s+bis\\s+(?:zum|weihnachten))\\b|©"
            + "|www:\\s*https?"
            + "|^\\s*(?:abb|abbildung|fig|figure|tab|tabelle)\\.?\\s*\\d|^\\s*\\p{Lu}\\p{Ll}+,\\s+\\p{Lu}\\.(?:\\s|,|$)");
    /**
     * Site notices that are also words of prose on the matter: a text on data protection names cookies, privacy
     * settings and the withdrawal of consent, a guideline its FAQ, a text on publishing a licence. Picture credits,
     * the copyright sign, a law's header, a figure label or a reference entry at the start are no such words: they
     * stand on page furniture only.
     */
    private static final Pattern PROSE_WORD_NOTICE = Pattern.compile("(?iu)cookies?|faq|häufig\\s+gestellte\\s+fragen"
            + "|frequently\\s+asked\\s+questions|startseite|impressum|datenschutzeinstellungen"
            + "|privatsphäre-?einstellungen|einwilligung\\s+widerrufen|creative\\s+commons|cc[\\s-]by(?:-\\S+)?"
            + "|licensed\\s+under|lizenziert\\s+unter|unter\\s+der\\s+lizenz");
    private static final Pattern LINK = Pattern.compile("(?i)https?://|www\\.|\\bwww\\b");

    /**
     * Why a passage that passed the prose rules is advertising or navigation: too many promotion signals, too many
     * words in capitals, or too many links; empty otherwise.
     */
    private Finding promotion(String t, String[] words, int count, boolean spareProseWords) {
        int notices = 0;
        String last = "";
        Matcher notice = SITE_NOTICE.matcher(t);
        while (notice.find()) {
            notices++;
            last = notice.group().trim();
        }
        boolean spared = spareProseWords && notices == 1 && PROSE_WORD_NOTICE.matcher(last).matches();
        if (notices > criteria.maxSiteNotices && !spared) {
            return new Finding(Rule.SITE_NOTICES, notices + " site notices > " + criteria.maxSiteNotices);
        }
        if (criteria.maxPromotionSignals > 0) {
            int signals = repeatedWordRuns(words);
            Matcher promotion = PROMOTION.matcher(t);
            while (promotion.find()) {
                signals++;
            }
            if (signals > criteria.maxPromotionSignals) {
                return new Finding(Rule.PROMOTION, signals + " promotion signals > " + criteria.maxPromotionSignals);
            }
            // A short passage (caption length) with one signal is a slogan or a teaser, not a statement.
            if (signals > 0 && count <= criteria.captionMaxWords) {
                return new Finding(Rule.PROMOTION, signals + " promotion signal in " + count + " words");
            }
        }
        if (criteria.maxUppercaseShare < 1) {
            int long3 = 0;
            int capitals = 0;
            for (String word : words) {
                String letters = word.replaceAll("[^\\p{L}]", "");
                if (letters.length() >= 3) {
                    long3++;
                    if (letters.equals(letters.toUpperCase(java.util.Locale.ROOT))
                            && !letters.equals(letters.toLowerCase(java.util.Locale.ROOT))) {
                        capitals++;
                    }
                }
            }
            if (long3 > 0 && capitals > criteria.maxUppercaseShare * long3) {
                return new Finding(Rule.CAPITALS, capitals + " of " + long3 + " words in capitals");
            }
        }
        if (criteria.maxLinkDensity < 1 && count > 0) {
            int links = 0;
            Matcher link = LINK.matcher(t);
            while (link.find()) {
                links++;
            }
            if (links > criteria.maxLinkDensity * count) {
                return new Finding(Rule.LINKS, links + " links in " + count + " words");
            }
        }
        return Finding.PROSE;
    }

    /**
     * Runs of one word written three times in a row ("teilen teilen teilen"), each a navigation signal: share buttons
     * and menus repeat their labels, sentences do not.
     */
    private static int repeatedWordRuns(String[] words) {
        int runs = 0;
        for (int i = 2; i < words.length; i++) {
            String w = words[i].toLowerCase(java.util.Locale.ROOT);
            if (LETTER.matcher(w).find() && w.equals(words[i - 1].toLowerCase(java.util.Locale.ROOT))
                    && w.equals(words[i - 2].toLowerCase(java.util.Locale.ROOT))
                    && (i < 3 || !w.equals(words[i - 3].toLowerCase(java.util.Locale.ROOT)))) {
                runs++;
            }
        }
        return runs;
    }

    /** The words of a text: its white-space separated tokens that hold a letter. */
    static int words(String text) {
        String t = text == null ? "" : text.replace("\u00AD", "").trim();
        int count = 0;
        for (String token : t.isEmpty() ? new String[0] : t.split("\\s+")) {
            if (LETTER.matcher(token).find()) {
                count++;
            }
        }
        return count;
    }

    /**
     * The captions of one page (indices into {@code page}, its passages in page order): on a page where fewer than
     * {@link Criteria#getCaptionPageProseShare() the share} of the passages are prose, a prose passage of at most
     * {@link Criteria#getCaptionMaxWords() caption words} whose neighbours on the page are no prose. Live run 4: the
     * shop page of mediencenter.bgrci.de (996 passages, 27 of them prose) put calendar slogans between product lines
     * ("Refresh your first aid training regularly to be prepared for emergencies."); seven of them formed a topic
     * of their own and became a section of the outline.
     */
    public Set<Integer> captions(List<String> page) {
        if (page == null || page.isEmpty() || criteria.captionPageProseShare <= 0) {
            return Collections.emptySet();
        }
        boolean[] prose = new boolean[page.size()];
        int count = 0;
        for (int i = 0; i < prose.length; i++) {
            prose[i] = isProse(page.get(i));
            count += prose[i] ? 1 : 0;
        }
        if (count >= criteria.captionPageProseShare * page.size()) {
            return Collections.emptySet();
        }
        Set<Integer> captions = new TreeSet<Integer>();
        for (int i = 0; i < prose.length; i++) {
            if (prose[i] && (i == 0 || !prose[i - 1]) && (i == prose.length - 1 || !prose[i + 1])
                    && words(page.get(i)) <= criteria.captionMaxWords) {
                captions.add(i);
            }
        }
        return captions;
    }
}
