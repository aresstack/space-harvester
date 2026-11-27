package com.aresstack.writing4j.planwriter;

import com.aresstack.knowledge4j.file.AtomicFiles;
import com.aresstack.knowledge4j.file.CorruptFileException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The written sections of one project: {@code section-writer/sections.jsonl} in the project directory (next to
 * {@code section-plan/}). One JSON object per line, UTF-8, one per written section ({@code "art":"abschnitt"}) with
 * its time and the section as {@link WrittenSection#toJson()} gives it. The latest line of a section holds; the earlier
 * ones stay in the file, so every version the writer wrote stays readable.
 * <p>
 * The rules of the other logs of the build plan: only appended to; a line that cannot be read fails closed
 * ({@link CorruptFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code section-writer/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written. Safe for parallel callers.
 */
public final class WrittenSections {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "sections.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private final Map<String, WrittenSection> latest = new LinkedHashMap<String, WrittenSection>();
    private int size;
    private boolean opened;

    /** Sections in RAM only, for tests and dry runs: open and writable at once, nothing goes to disk. */
    public WrittenSections() {
        this.logFile = null;
        this.pointerFile = null;
        this.opened = true;
    }

    /** The written sections of a project; {@link #open()} reads them before the first one is written. */
    public WrittenSections(File projectDirectory) {
        File folder = new File(projectDirectory, "section-writer");
        this.logFile = new File(folder, GENERATION);
        this.pointerFile = new File(folder, "current");
    }

    /** The file, {@code null} for sections in RAM only. */
    public File logFile() {
        return logFile;
    }

    /** The file was written by a newer Writing4j: this build neither reads nor changes it. */
    public static final class NewerVersionException extends Exception {
        private final File file;

        NewerVersionException(File file, String what) {
            super("The written sections were written by a newer Writing4j (" + what + "; this build reads versions 1 to "
                    + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /**
     * Reads every written section; writing is allowed from now on. Nothing to read before the first section.
     *
     * @return the number of lines read
     */
    public synchronized int open() throws IOException, CorruptFileException, NewerVersionException {
        if (logFile == null) {
            return size;
        }
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        Map<String, WrittenSection> read = new LinkedHashMap<String, WrittenSection>();
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
                if (!record.has("art") || !"abschnitt".equals(record.get("art").getAsString())
                        || !record.has("abschnitt") || !record.get("abschnitt").isJsonObject()) {
                    throw corrupt(lineNumber, "not a written section");
                }
                WrittenSection section;
                try {
                    section = WrittenSection.fromJson(record.getAsJsonObject("abschnitt"));
                } catch (RuntimeException unreadable) {
                    throw corrupt(lineNumber, unreadable.getMessage());
                }
                read.remove(section.getSectionId());
                read.put(section.getSectionId(), section);
                lines++;
            }
            if (end < content.length()) {
                System.err.println("[section-writer] dropping an unfinished last record of " + logFile);
                AtomicFiles.write(logFile, content.substring(0, end));
            }
        }
        latest.clear();
        latest.putAll(read);
        size = lines;
        opened = true;
        return lines;
    }

    /** One written section, written and flushed to disk as one line before it holds. Only after {@link #open()}. */
    public synchronized void record(WrittenSection section) throws IOException {
        if (section == null) {
            throw new IllegalArgumentException("no section");
        }
        if (!opened) {
            throw new IllegalStateException("open the written sections before writing to them");
        }
        if (logFile != null) {
            JsonObject line = new JsonObject();
            line.addProperty("v", VERSION);
            line.addProperty("art", "abschnitt");
            line.addProperty("zeit", section.getTimeMillis());
            line.add("abschnitt", section.toJson());
            Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
            FileOutputStream out = new FileOutputStream(logFile, true);
            try {
                out.write((GSON.toJson(line) + "\n").getBytes(AtomicFiles.UTF8));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        }
        latest.remove(section.getSectionId());
        latest.put(section.getSectionId(), section);
        size++;
    }

    /** The latest written version of a section, {@code null} when it was never written. */
    public synchronized WrittenSection latest(String sectionId) {
        return latest.get(sectionId);
    }

    /** The latest version of every written section, the latest written last. */
    public synchronized List<WrittenSection> all() {
        return new ArrayList<WrittenSection>(latest.values());
    }

    /** The number of lines: every version of every section. */
    public synchronized int size() {
        return size;
    }

    private CorruptFileException corrupt(int lineNumber, String why) {
        return new CorruptFileException(logFile, "line " + lineNumber + " of the written sections cannot be "
                + "read (" + why + ")");
    }
}
