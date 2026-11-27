package com.aresstack.askai.research.runtime.sourcerating;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component 16: where the source ratings live. One JSON object per line in {@code source-ratings/log.jsonl} of the
 * research folder (next to {@code search-plan/}), appended and never rewritten (contracts of the build plan, rev 13).
 * A later line for the same source wins; a {@link SourceRating.Status#FEHLER} line stays readable and is offered
 * again. Reading fails closed: a line that is no valid entry stops the read with its number, except an unfinished
 * last line (a write that broke off), which is skipped. A line of a newer format ({@code "v"} above
 * {@link #VERSION}) stops the read with {@link NewerVersionException}: an older app must not overwrite what it cannot
 * read. Writes of one log are serialized; the log keeps no state between calls.
 */
public final class SourceRatingLog {

    /** The folder in the research folder. */
    public static final String FOLDER = "source-ratings";
    /** The file in that folder. */
    public static final String FILE = "log.jsonl";
    /** The format this code writes and reads. */
    public static final int VERSION = 1;
    private static final String KIND = "quelle";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** A line was written by a newer app. */
    public static final class NewerVersionException extends IOException {
        public NewerVersionException(String message) {
            super(message);
        }
    }

    private final Path file;

    /** @param researchFolder the folder that holds {@code search-plan/} */
    public SourceRatingLog(Path researchFolder) {
        this.file = researchFolder.resolve(FOLDER).resolve(FILE);
    }

    public Path getFile() {
        return file;
    }

    /**
     * Appends one line per rating, in the given order. A log with a line of a newer app is left untouched
     * ({@link NewerVersionException}).
     */
    public synchronized void append(List<SourceRating> ratings) throws IOException {
        if (ratings.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (SourceRating rating : ratings) {
            text.append(line(rating)).append('\n');
        }
        Files.createDirectories(file.getParent());
        try {
            readAll();
        } catch (NewerVersionException newer) {
            throw newer; // a line of a newer app stops the append before anything is cut or written
        } catch (IOException broken) {
            // a broken line stops the next read, as before; it does not stop the append
        }
        dropUnfinishedLastLine();
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    /** Every entry in file order; empty when the log does not exist yet. */
    public synchronized List<SourceRating> readAll() throws IOException {
        List<SourceRating> out = new ArrayList<SourceRating>();
        if (!Files.exists(file)) {
            return out;
        }
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        boolean finished = content.endsWith("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            boolean last = i == lines.length - 1;
            try {
                out.add(parse(line));
            } catch (NewerVersionException newer) {
                throw new NewerVersionException(file + " line " + (i + 1) + ": " + newer.getMessage());
            } catch (RuntimeException broken) {
                if (last && !finished) {
                    continue; // a write that broke off: the rating is offered again
                }
                throw new IOException(file + " line " + (i + 1) + " is no valid source rating: "
                        + broken.getMessage(), broken);
            }
        }
        return out;
    }

    /** The latest entry of each source, in the order the sources first appear. */
    public Map<String, SourceRating> latestBySource() throws IOException {
        Map<String, SourceRating> out = new LinkedHashMap<String, SourceRating>();
        for (SourceRating rating : readAll()) {
            out.put(rating.getSourceId(), rating);
        }
        return out;
    }

    /**
     * For {@link SourceRater#rateAll}: the latest valid rating of each host. A host whose latest rating failed is left
     * out, so its group is asked again. Of one rating and its copies for the other pages of the host, the entry of the
     * page the model read is kept, so that page keeps the date the model found when the rating is reused.
     */
    public Map<String, SourceRating> latestOkByHost() throws IOException {
        Map<String, SourceRating> out = new LinkedHashMap<String, SourceRating>();
        for (SourceRating rating : readAll()) {
            String host = rating.getHost().isEmpty() ? "adresse:" + rating.getAddress() : rating.getHost();
            if (rating.isOk()) {
                SourceRating held = out.get(host);
                boolean copyOfHeld = held != null && held.getSourceId().equals(held.getSampleId())
                        && held.getSampleId().equals(rating.getSampleId())
                        && !rating.getSourceId().equals(rating.getSampleId());
                if (!copyOfHeld) {
                    out.put(host, rating); // the page the model read wins over the copies of its rating
                }
            } else {
                out.remove(host);
            }
        }
        return out;
    }

    /**
     * A last line without its newline is a write that broke off; reading skips it. Before the next append it is cut
     * off, so it never becomes a broken line in the middle (which reading would refuse).
     */
    private void dropUnfinishedLastLine() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        int keep = bytes.length;
        while (keep > 0 && bytes[keep - 1] != '\n') {
            keep--;
        }
        if (keep < bytes.length) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(keep);
            }
        }
    }

    static String line(SourceRating r) {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("art", KIND);
        o.addProperty("quelleId", r.getSourceId());
        o.addProperty("adresse", r.getAddress());
        o.addProperty("domain", r.getHost());
        o.addProperty("titel", r.getTitle());
        o.addProperty("quellenart", r.getKind() == null ? "" : r.getKind().name());
        o.addProperty("rechtsraum", r.getJurisdiction() == null ? "" : r.getJurisdiction().name());
        o.addProperty("datum", r.getDate());
        o.addProperty("eigeninteresse", r.getSelfInterest() == null ? "" : r.getSelfInterest().name());
        o.addProperty("anbieter", r.getPublisher());
        o.addProperty("grund", r.getReason());
        KnownDomains.Entry suggestion = r.getSuggestion();
        if (suggestion != null) {
            JsonObject s = new JsonObject();
            s.addProperty("domain", suggestion.getDomain());
            s.addProperty("quellenart", suggestion.getKind().name());
            s.addProperty("rechtsraum", suggestion.getJurisdiction().name());
            s.addProperty("hinweis", suggestion.getNote());
            o.add("vorschlag", s);
        }
        o.addProperty("modell", r.getModel());
        o.addProperty("zeit", r.getTime());
        o.addProperty("status", r.getStatus().name());
        o.addProperty("fehler", r.getProblem());
        o.addProperty("beispiel", r.getSampleId());
        JsonArray answers = new JsonArray();
        for (String answer : r.getAnswers()) {
            answers.add(answer);
        }
        o.add("rohantworten", answers);
        return GSON.toJson(o);
    }

    static SourceRating parse(String line) throws NewerVersionException {
        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
        int version = o.get("v").getAsInt();
        if (version > VERSION) {
            throw new NewerVersionException("format " + version + " is newer than " + VERSION
                    + "; update the app before it writes here");
        }
        if (!KIND.equals(o.get("art").getAsString())) {
            throw new IllegalArgumentException("not a source rating: " + o.get("art"));
        }
        SourceRating.Status status = SourceRating.Status.valueOf(o.get("status").getAsString());
        SourceRating.Kind kind = optionalEnum(o, "quellenart", SourceRating.Kind.class);
        SourceRating.Jurisdiction jurisdiction = optionalEnum(o, "rechtsraum", SourceRating.Jurisdiction.class);
        SourceRating.SelfInterest selfInterest = optionalEnum(o, "eigeninteresse", SourceRating.SelfInterest.class);
        if (status == SourceRating.Status.OK && (kind == null || jurisdiction == null || selfInterest == null)) {
            throw new IllegalArgumentException("a valid rating without kind, jurisdiction or self-interest");
        }
        KnownDomains.Entry suggestion = null;
        JsonElement s = o.get("vorschlag");
        if (s != null && s.isJsonObject()) {
            JsonObject so = s.getAsJsonObject();
            suggestion = new KnownDomains.Entry(text(so, "domain"),
                    SourceRating.Kind.valueOf(text(so, "quellenart")),
                    SourceRating.Jurisdiction.valueOf(text(so, "rechtsraum")), text(so, "hinweis"));
        }
        List<String> answers = new ArrayList<String>();
        JsonElement raw = o.get("rohantworten");
        if (raw != null && raw.isJsonArray()) {
            for (JsonElement answer : raw.getAsJsonArray()) {
                answers.add(answer.getAsString());
            }
        }
        String sourceId = text(o, "quelleId");
        if (sourceId.isEmpty()) {
            throw new IllegalArgumentException("no quelleId");
        }
        return new SourceRating(sourceId, text(o, "adresse"), text(o, "domain"), text(o, "titel"), kind,
                jurisdiction, text(o, "datum"), selfInterest, text(o, "anbieter"), text(o, "grund"), suggestion,
                text(o, "modell"), text(o, "zeit"), status, text(o, "fehler"), text(o, "beispiel"), answers);
    }

    private static <E extends Enum<E>> E optionalEnum(JsonObject o, String field, Class<E> type) {
        String value = text(o, field);
        return value.isEmpty() ? null : Enum.valueOf(type, value);
    }

    private static String text(JsonObject o, String field) {
        JsonElement value = o.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
