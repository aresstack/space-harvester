package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WHICH engines are used and IN WHICH ORDER, plus how they are worked through. This replaces the old
 * "primary engine plus fallback templates" idea: there is no privileged engine and no second-class one,
 * only an ordered set of enabled engines and a strategy.
 * <p>
 * The order is the execution order — what the user arranges is what happens, not a hint.
 */
public final class BrowserSearchEngineSelection {

    /**
     * One row of the user's engine table (Search → Engines): which catalog engine, whether it takes part, its
     * priority (0 runs first; rows of equal priority form one group), how many result pages, how long to pause
     * before every FURTHER request to this engine (anti-captcha pacing), and the languages it is searched in. The
     * same engine may have several rows, e.g. Google in German at priority 1 and in French at priority 3.
     * <p>
     * A row is a configuration, not an engine: it points at a {@link SearchEngineCatalog} entry by id.
     */
    public static final class Entry {

        /** Result pages fetched per engine when the configuration names none — a SETTING's fallback. */
        public static final int DEFAULT_RESULT_PAGES = 3;

        /**
         * No extra pause between requests to the same engine unless the user asks for one — or the catalog names
         * one for the engine ({@link SearchEngineCatalog#defaultDelayMillis}), which a row without a delay of its
         * own starts with.
         */
        public static final int DEFAULT_DELAY_MILLIS = 0;

        /**
         * A row read from a configuration written before priorities existed; the selection gives it the priority
         * of its list position ({@link BrowserSearchEngineSelection#BrowserSearchEngineSelection}).
         */
        public static final int UNSET_PRIORITY = -1;

        private final String engineId;
        private final boolean enabled;
        private final int resultPages;
        private final int delayMillis;
        private final int priority;
        private final List<String> languages;
        private final List<String> furtherLanguages;

        /** A row with the catalog's result pages and delay for the engine. */
        public Entry(String engineId, boolean enabled) {
            this(engineId, enabled, DEFAULT_RESULT_PAGES, SearchEngineCatalog.defaultDelayMillis(engineId));
        }

        /** A row with the catalog's delay for the engine. */
        public Entry(String engineId, boolean enabled, int resultPages) {
            this(engineId, enabled, resultPages, SearchEngineCatalog.defaultDelayMillis(engineId));
        }

        /** A row without priority and languages: priority from its list position, languages from the catalog. */
        public Entry(String engineId, boolean enabled, int resultPages, int delayMillis) {
            this(engineId, enabled, resultPages, delayMillis, UNSET_PRIORITY,
                    SearchEngineCatalog.defaultLanguages(engineId), Collections.<String>emptyList());
        }

        /**
         * @param priority                0 = first group; {@link #UNSET_PRIORITY} = take the list position
         * @param languages               the row's picked search languages (normalised, duplicates dropped)
         * @param furtherLanguages        free language codes beyond the picker; empty = none
         */
        public Entry(String engineId, boolean enabled, int resultPages, int delayMillis, int priority,
                     List<String> languages, List<String> furtherLanguages) {
            if (engineId == null || engineId.trim().isEmpty()) {
                throw new IllegalArgumentException("engine id must not be empty");
            }
            this.engineId = engineId.trim();
            this.enabled = enabled;
            this.resultPages = resultPages > 0 ? resultPages : DEFAULT_RESULT_PAGES;
            this.delayMillis = delayMillis > 0 ? delayMillis : DEFAULT_DELAY_MILLIS;
            this.priority = priority >= 0 ? priority : UNSET_PRIORITY;
            this.languages = Collections.unmodifiableList(new ArrayList<String>(
                    SearchLanguages.parse(languages).getCodes()));
            this.furtherLanguages = Collections.unmodifiableList(new ArrayList<String>(
                    SearchLanguages.parse(furtherLanguages).getCodes()));
        }

        /** The same row with another priority. */
        public Entry withPriority(int newPriority) {
            return new Entry(engineId, enabled, resultPages, delayMillis, newPriority, languages,
                    furtherLanguages);
        }

        public String getEngineId() {
            return engineId;
        }

        public boolean isEnabled() {
            return enabled;
        }

        /** How many result pages of this engine one search fetches (always >= 1). */
        public int getResultPages() {
            return resultPages;
        }

        /** Pause before every request to this engine after the first, in milliseconds (0 = off). */
        public int getDelayMillis() {
            return delayMillis;
        }

        /** 0 = first group, or {@link #UNSET_PRIORITY} for a row not yet placed by a selection. */
        public int getPriority() {
            return priority;
        }

        /** The picked languages (normalised); may be empty = no language preference. */
        public List<String> getLanguages() {
            return languages;
        }

        /** The free further codes as entered (normalised); empty = none. */
        public List<String> getFurtherLanguages() {
            return furtherLanguages;
        }

        /** The languages this row searches in: the picked ones plus the further ones. */
        public List<String> effectiveLanguages() {
            List<String> all = new ArrayList<String>(languages);
            all.addAll(furtherLanguages);
            return SearchLanguages.parse(all).getCodes();
        }
    }

    // ------------------------------------------------------------------ delay <-> seconds text

    /**
     * The delay as the seconds text the user reads and writes: up to three decimals, trailing zeros
     * (and a bare decimal point) removed — {@code 1500 -> "1.5"}, {@code 250 -> "0.25"},
     * {@code 2000 -> "2"}, {@code 0 -> "0"}.
     */
    public static String formatDelaySeconds(int delayMillis) {
        String text = String.format(java.util.Locale.ROOT, "%.3f", delayMillis / 1000.0);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** Seconds text (decimal point or comma) back to milliseconds; unreadable or negative means OFF. */
    public static int parseDelayMillis(String secondsText) {
        if (secondsText == null) {
            return Entry.DEFAULT_DELAY_MILLIS;
        }
        try {
            double seconds = Double.parseDouble(secondsText.trim().replace(',', '.'));
            return seconds > 0 ? (int) Math.round(seconds * 1000.0) : Entry.DEFAULT_DELAY_MILLIS;
        } catch (NumberFormatException invalid) {
            return Entry.DEFAULT_DELAY_MILLIS;
        }
    }

    private final List<Entry> entries;
    private final EngineAcquisitionMode mode;

    /**
     * Rows without a priority (a configuration from before priorities) get their list position as priority, so the
     * old order stays the execution order. The rows are then held sorted by priority ascending; rows of the same
     * priority keep the order they were stored in. That stored order is observable (a browser types the engines of
     * one group one after another and the attempt budget is spent in that order), so loading a configuration never
     * reorders it. The engine table writes its rows sorted by priority, then engine name, so a table edited by the
     * user is stored in exactly the order it shows.
     */
    public BrowserSearchEngineSelection(List<Entry> entries, EngineAcquisitionMode mode) {
        List<Entry> placed = new ArrayList<Entry>();
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if (entry != null) {
                    placed.add(entry.getPriority() == Entry.UNSET_PRIORITY ? entry.withPriority(i) : entry);
                }
            }
        }
        this.entries = Collections.unmodifiableList(sorted(placed));
        this.mode = mode == null ? EngineAcquisitionMode.FIRST_USABLE : mode;
    }

    /** Priority ascending; rows of the same priority keep the given order (the sort is stable). */
    public static List<Entry> sorted(List<Entry> entries) {
        List<Entry> copy = new ArrayList<Entry>(entries == null ? Collections.<Entry>emptyList() : entries);
        Collections.sort(copy, new java.util.Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                return a.getPriority() < b.getPriority() ? -1 : a.getPriority() == b.getPriority() ? 0 : 1;
            }
        });
        return copy;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public EngineAcquisitionMode getMode() {
        return mode;
    }

    /**
     * The engines to actually visit, in order, each once. Rows whose engine has no browser driver (an engine of
     * the catalog that is not implemented yet, or an id from a newer build) are skipped.
     */
    public List<BrowserSearchEngine> resolvedEnabledEngines() {
        List<BrowserSearchEngine> resolved = new ArrayList<BrowserSearchEngine>();
        for (Entry entry : entries) {
            if (!entry.isEnabled()) {
                continue;
            }
            BrowserSearchEngine engine = BrowserSearchEngineCatalog.byId(entry.getEngineId());
            if (engine != null && !resolved.contains(engine)) { // a second row of the same engine: visited once
                resolved.add(engine);
            }
        }
        return resolved;
    }

    // ------------------------------------------------------------------ flat-string form (settings codec)

    /**
     * One row per comma-separated piece: {@code id:on|off:pages:delay:priority:languages:further}.
     * The delay is in seconds (up to three decimals), languages are joined with {@code +}:
     * {@code "google:on:1:15:0:de+en:,duckduckgo:on:3:0:1:de+en:pl+uk"}. The rows are written in table
     * order (priority, name).
     */
    public String encodeEntries() {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(escapeId(entry.getEngineId())).append(':').append(entry.isEnabled() ? "on" : "off")
                    .append(':').append(entry.getResultPages())
                    .append(':').append(formatDelaySeconds(entry.getDelayMillis()))
                    .append(':').append(entry.getPriority())
                    .append(':').append(joinCodes(entry.getLanguages()))
                    .append(':').append(joinCodes(entry.getFurtherLanguages()));
        }
        return sb.toString();
    }

    /**
     * A stored engine list in the current form. A list from before priorities ({@code id:on:pages[:delay]}) is
     * migrated so that it searches as before: under {@link EngineAcquisitionMode#ALL_ENABLED} every row gets
     * priority 0 (one group, all merged), otherwise each row its list position (tried in order). Languages of
     * migrated rows come from the catalog, and so does the delay of a row from before delays existed
     * ({@code id:on:pages}): a Google row that never had one gets {@link SearchEngineCatalog#GOOGLE_REQUEST_DELAY_MILLIS}
     * — the old form could not express a pause, so the stored list carries no choice to keep. A list already in
     * the current form is only normalised.
     */
    public static String migrate(String encoded, EngineAcquisitionMode mode) {
        List<Entry> parsed = parseEntries(encoded);
        if (mode == EngineAcquisitionMode.ALL_ENABLED) {
            List<Entry> flat = new ArrayList<Entry>();
            for (Entry entry : parsed) {
                flat.add(entry.getPriority() == Entry.UNSET_PRIORITY ? entry.withPriority(0) : entry);
            }
            parsed = flat;
        }
        return new BrowserSearchEngineSelection(parsed, mode).encodeEntries();
    }

    /**
     * An engine id in the flat form: a search page's URL ({@link BrowserSearchEngineCatalog#isSearchPage}) carries
     * {@code :} and {@code ,}, the separators of the form, so they (and {@code %}) are written percent-encoded.
     * Catalog ids contain none of them and stay as they are. The same form carries ids in a comma-separated tool
     * argument ({@code engines} of web_search and web_search_prepare).
     */
    public static String escapeId(String id) {
        return id.replace("%", "%25").replace(":", "%3A").replace(",", "%2C");
    }

    /** The id an {@link #escapeId escaped} id stands for. */
    public static String unescapeId(String id) {
        return id.replace("%3A", ":").replace("%3a", ":").replace("%2C", ",").replace("%2c", ",")
                .replace("%25", "%");
    }

    private static String joinCodes(List<String> codes) {
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(code);
        }
        return sb.toString();
    }

    private static List<String> splitCodes(String text) {
        return SearchLanguages.parse(text == null ? "" : text.replace('+', ',')).getCodes();
    }

    /**
     * The configured result-page count for this engine: the value of its first row (the highest priority), else the
     * default.
     */
    public int resultPagesFor(String engineId) {
        for (Entry entry : entries) {
            if (entry.getEngineId().equals(engineId)) {
                return entry.getResultPages();
            }
        }
        return Entry.DEFAULT_RESULT_PAGES;
    }

    /** The configured pause before repeat requests to this engine (its first row), in milliseconds (0 = off). */
    public int delayMillisFor(String engineId) {
        for (Entry entry : entries) {
            if (entry.getEngineId().equals(engineId)) {
                return entry.getDelayMillis();
            }
        }
        return Entry.DEFAULT_DELAY_MILLIS;
    }

    /**
     * Parse the flat form; unreadable pieces are skipped rather than failing the whole configuration. Older, shorter
     * rows ({@code "id"}, {@code "id:on"}, {@code "id:on:3"}, {@code "id:on:3:1.5"}) keep the documented defaults for
     * the parts they do not carry: no priority yet ({@link Entry#UNSET_PRIORITY}), the catalog's languages and,
     * for a row without a delay field, the catalog's delay for the engine.
     */
    public static List<Entry> parseEntries(String encoded) {
        List<Entry> entries = new ArrayList<Entry>();
        if (encoded == null || encoded.trim().isEmpty()) {
            return entries;
        }
        for (String piece : encoded.split(",")) {
            String text = piece.trim();
            if (text.isEmpty()) {
                continue;
            }
            String[] parts = text.split(":", -1);
            String id = unescapeId(parts[0].trim());
            if (id.isEmpty()) {
                continue;
            }
            boolean enabled = parts.length < 2 || !"off".equalsIgnoreCase(parts[1].trim());
            int pages = Entry.DEFAULT_RESULT_PAGES;
            if (parts.length >= 3) {
                try {
                    pages = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException invalid) {
                    pages = Entry.DEFAULT_RESULT_PAGES;
                }
            }
            int delayMillis = parts.length >= 4 ? parseDelayMillis(parts[3])
                    : SearchEngineCatalog.defaultDelayMillis(id);
            int priority = Entry.UNSET_PRIORITY;
            if (parts.length >= 5) {
                try {
                    priority = Integer.parseInt(parts[4].trim());
                } catch (NumberFormatException invalid) {
                    priority = Entry.UNSET_PRIORITY;
                }
            }
            List<String> languages = parts.length >= 6 ? splitCodes(parts[5])
                    : SearchEngineCatalog.defaultLanguages(id);
            entries.add(new Entry(id, enabled, pages, delayMillis, priority, languages, furtherLanguages(parts)));
        }
        return entries;
    }

    /**
     * The further-languages field of a row. A row stored while the table still had a separate on/off switch
     * ({@code ...:languages:on|off:codes}) keeps its codes only when they were switched on, so it searches as before.
     */
    private static List<String> furtherLanguages(String[] parts) {
        if (parts.length >= 8) {
            return "on".equalsIgnoreCase(parts[6].trim()) ? splitCodes(parts[7]) : Collections.<String>emptyList();
        }
        return parts.length >= 7 ? splitCodes(parts[6]) : Collections.<String>emptyList();
    }
}
