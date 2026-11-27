package com.aresstack.writing4j.sentencecheck;

import com.aresstack.knowledge4j.file.AtomicFiles;
import com.aresstack.knowledge4j.file.CorruptFileException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sentence check's verdicts of one project, by sentence and passage text: {@code sentence-check/verdicts.jsonl} in
 * the project directory (next to {@code research-memory/}). One JSON object per line, UTF-8, one per verdict
 * ({@code "art":"urteil"}): sentence id and key, passage, source, text key, the model's verdict and the one after the
 * quote check, quote, reason, kind of text, attribution, legal space, status, problem, model, the check's version,
 * time, duration and the model's raw answers. The latest verdict of a sentence and passage text holds; the earlier
 * ones stay in the file.
 * <p>
 * The rules of the other logs of the build plan: only appended to; a line that cannot be read fails closed
 * ({@link CorruptFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code sentence-check/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written. Safe for parallel callers.
 */
public final class SentenceVerdicts {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "verdicts.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private final Map<String, SentenceVerdict> verdicts = new LinkedHashMap<String, SentenceVerdict>();
    private boolean opened;

    /** Verdicts in RAM only, for tests and dry runs: open and writable at once, nothing goes to disk. */
    public SentenceVerdicts() {
        this.logFile = null;
        this.pointerFile = null;
        this.opened = true;
    }

    /** The verdicts of a project; {@link #open()} reads them before the first one is written. */
    public SentenceVerdicts(File projectDirectory) {
        File folder = new File(projectDirectory, "sentence-check");
        this.logFile = new File(folder, GENERATION);
        this.pointerFile = new File(folder, "current");
    }

    /** The file, {@code null} for verdicts in RAM only. */
    public File logFile() {
        return logFile;
    }

    /** The file was written by a newer Writing4j: this build neither reads nor changes it. */
    public static final class NewerVersionException extends Exception {
        private final File file;

        NewerVersionException(File file, String what) {
            super("The sentence check's verdicts were written by a newer Writing4j (" + what + "; this build reads "
                    + "versions 1 to " + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /** The key of a sentence and a passage text. */
    static String key(String sentenceKey, String textKey) {
        return sentenceKey + "\n" + textKey;
    }

    /**
     * Reads every verdict, the latest per sentence and passage text holds; writing is allowed from now on. Nothing to
     * read before the first verdict.
     *
     * @return the number of lines read
     */
    public synchronized int open() throws IOException, CorruptFileException, NewerVersionException {
        if (logFile == null) {
            return 0;
        }
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        Map<String, SentenceVerdict> read = new LinkedHashMap<String, SentenceVerdict>();
        int lines = 0;
        if (logFile.isFile()) {
            String content = new String(Files.readAllBytes(logFile.toPath()), AtomicFiles.UTF8);
            int end = content.lastIndexOf('\n') + 1;
            int lineNumber = 0;
            for (String line : content.substring(0, end).split("\n")) {
                lineNumber++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonObject record;
                int version;
                try {
                    record = JsonParser.parseString(line).getAsJsonObject();
                    version = record.get("v").getAsInt();
                } catch (RuntimeException unreadable) {
                    throw corrupt(lineNumber, unreadable.getMessage());
                }
                if (version > VERSION) {
                    throw new NewerVersionException(logFile, "line " + lineNumber + " has version " + version);
                }
                if (!"urteil".equals(string(record, "art"))) {
                    throw corrupt(lineNumber, "unknown record kind");
                }
                SentenceVerdict verdict;
                try {
                    verdict = fromJson(record);
                } catch (RuntimeException unreadable) {
                    throw corrupt(lineNumber, unreadable.getMessage());
                }
                String key = key(verdict.getSentenceKey(), verdict.getTextKey());
                read.remove(key);
                read.put(key, verdict);
                lines++;
            }
            if (end < content.length()) {
                System.err.println("[sentence-check] dropping an unfinished last record of " + logFile);
                AtomicFiles.write(logFile, content.substring(0, end));
            }
        }
        verdicts.clear();
        verdicts.putAll(read);
        opened = true;
        return lines;
    }

    /** One verdict without raw answers. */
    public void record(SentenceVerdict verdict) throws IOException {
        record(verdict, Collections.<String>emptyList());
    }

    /**
     * One verdict, written and flushed to disk as one line before it holds. Only after {@link #open()}; a verdict that
     * cannot be written does not hold.
     */
    public synchronized void record(SentenceVerdict verdict, List<String> answers) throws IOException {
        if (verdict == null) {
            throw new IllegalArgumentException("no verdict");
        }
        if (!opened) {
            throw new IllegalStateException("open the sentence check's verdicts before writing to them");
        }
        if (logFile != null) {
            Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
            FileOutputStream out = new FileOutputStream(logFile, true);
            try {
                out.write((GSON.toJson(toJson(verdict, answers)) + "\n").getBytes(AtomicFiles.UTF8));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        }
        String key = key(verdict.getSentenceKey(), verdict.getTextKey());
        verdicts.remove(key);
        verdicts.put(key, verdict);
    }

    /** The verdict that holds for a sentence and a passage text, {@code null} when there is none. */
    public synchronized SentenceVerdict verdict(String sentenceKey, String textKey) {
        return verdicts.get(key(sentenceKey, textKey));
    }

    /** Every verdict that holds, the latest written last. */
    public synchronized List<SentenceVerdict> all() {
        return new ArrayList<SentenceVerdict>(verdicts.values());
    }

    /** The verdicts that hold for one sentence, one per passage text it was checked against. */
    public synchronized List<SentenceVerdict> ofSentence(String sentenceKey) {
        List<SentenceVerdict> out = new ArrayList<SentenceVerdict>();
        for (SentenceVerdict verdict : verdicts.values()) {
            if (verdict.getSentenceKey().equals(sentenceKey)) {
                out.add(verdict);
            }
        }
        return out;
    }

    /** The number of sentence and passage pairs with a verdict. */
    public synchronized int size() {
        return verdicts.size();
    }

    static JsonObject toJson(SentenceVerdict verdict, List<String> answers) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "urteil");
        json.addProperty("satz", verdict.getSentenceId());
        json.addProperty("satzSchluessel", verdict.getSentenceKey());
        json.addProperty("passage", verdict.getPassageId());
        json.addProperty("quelle", verdict.getSourceId());
        json.addProperty("text", verdict.getTextKey());
        json.addProperty("urteilModell", verdict.getSaid() == null ? "" : verdict.getSaid().name());
        json.addProperty("urteil", verdict.getVerdict() == null ? "" : verdict.getVerdict().name());
        json.addProperty("zitat", verdict.getQuote());
        json.addProperty("grund", verdict.getReason());
        json.addProperty("belegart", verdict.getKind() == null ? "" : verdict.getKind().name());
        json.addProperty("zuschreibung", verdict.getAttribution().name());
        json.addProperty("rechtsraum", verdict.getJurisdiction().name());
        json.addProperty("status", verdict.getStatus().name());
        json.addProperty("fehler", verdict.getProblem());
        json.addProperty("modell", verdict.getModel());
        json.addProperty("fassung", verdict.getVersion());
        json.addProperty("zeit", verdict.getTimeMillis());
        json.addProperty("ms", verdict.getMillis());
        JsonArray raw = new JsonArray();
        for (String answer : answers == null ? Collections.<String>emptyList() : answers) {
            raw.add(answer == null ? "" : answer);
        }
        json.add("rohantworten", raw);
        return json;
    }

    static SentenceVerdict fromJson(JsonObject json) {
        SentenceVerdict.Status status = SentenceVerdict.Status.valueOf(upper(json, "status"));
        String said = upper(json, "urteilModell");
        String holds = upper(json, "urteil");
        String kind = upper(json, "belegart");
        return new SentenceVerdict(string(json, "satz"), string(json, "satzSchluessel"), string(json, "passage"),
                string(json, "quelle"), string(json, "text"),
                said.isEmpty() ? null : SentenceVerdict.Verdict.valueOf(said),
                holds.isEmpty() ? null : SentenceVerdict.Verdict.valueOf(holds), string(json, "zitat"),
                string(json, "grund"), kind.isEmpty() ? null : SentenceVerdict.Kind.valueOf(kind),
                SentenceVerdict.Attribution.valueOf(upper(json, "zuschreibung")),
                SentenceVerdict.Jurisdiction.valueOf(upper(json, "rechtsraum")), status, string(json, "fehler"),
                string(json, "modell"), json.get("fassung").getAsInt(), json.get("zeit").getAsLong(),
                json.has("ms") ? json.get("ms").getAsLong() : 0L);
    }

    private CorruptFileException corrupt(int lineNumber, String why) {
        return new CorruptFileException(logFile, "The sentence check's verdicts cannot be read (line "
                + lineNumber + "): " + why);
    }

    private static String upper(JsonObject json, String field) {
        return string(json, field).toUpperCase(Locale.ROOT);
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }
}
