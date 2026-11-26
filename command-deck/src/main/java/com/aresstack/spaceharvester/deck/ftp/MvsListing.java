package com.aresstack.spaceharvester.deck.ftp;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.MvsDatasetListing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Liest die Zeilen von {@code LIST} auf MVS für die Detailansicht (RECFM, LRECL, Größe, Datum wie in MainframeMate):
 * Dataset-Listen ({@code Volume Unit Referred Ext Used Recfm Lrecl BlkSz Dsorg Dsname}) und Member-Listen mit
 * ISPF-Statistik ({@code Name VV.MM Created Changed Size Init Mod Id}).
 */
public final class MvsListing {

    /** Eine gelesene Zeile: Name (Dataset voll qualifiziert bzw. Member) und was dazu bekannt ist. */
    public record Line(String name, RemoteEntry.Details details, Optional<java.time.Instant> modified) {
    }

    private static final Pattern NUMBER = Pattern.compile("^\\d+$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}/\\d{2}/\\d{2}$");
    private static final Pattern VERSION = Pattern.compile("^\\d{2}\\.\\d{2}$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT);

    private MvsListing() {
    }

    /**
     * Eine Zeile einer Dataset-Liste; leer für Überschrift und Unlesbares. Die Spalten liest dieselbe Stelle wie Inventur
     * und Eignungsprüfung der Quelle ({@link MvsDatasetListing}); migrierte Datasets zeigen das Volume {@code MIGRAT}.
     */
    public static Optional<Line> dataset(String raw) {
        MvsDatasetListing.Dataset dataset = MvsDatasetListing.parse(raw);
        if (dataset == null) {
            return Optional.empty();
        }
        if (dataset.migrated()) {
            return Optional.of(new Line(dataset.name(), new RemoteEntry.Details("", 0, 0, "", "MIGRAT", 0, ""),
                    Optional.empty()));
        }
        return Optional.of(new Line(dataset.name(), new RemoteEntry.Details(dataset.recordFormat(),
                dataset.recordLength(), dataset.blockSize(), dataset.organization(), dataset.volume(), 0, ""),
                dataset.referred().isEmpty() ? Optional.empty() : day(dataset.referred())));
    }

    /** Eine Zeile einer Member-Liste (mit oder ohne ISPF-Statistik); leer für die Überschrift. */
    public static Optional<Line> member(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] tokens = raw.trim().split("\\s+");
        if (tokens[0].equalsIgnoreCase("Name") || !MvsPath.isMemberName(tokens[0].toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        String name = tokens[0].toUpperCase(Locale.ROOT);
        if (tokens.length >= 9 && VERSION.matcher(tokens[1]).matches() && DATE.matcher(tokens[3]).matches()) {
            Optional<java.time.Instant> changed = minute(tokens[3] + " " + tokens[4]);
            int lines = NUMBER.matcher(tokens[5]).matches() ? Integer.parseInt(tokens[5]) : 0;
            return Optional.of(new Line(name, new RemoteEntry.Details("", 0, 0, "", "", lines, tokens[8]), changed));
        }
        return Optional.of(new Line(name, RemoteEntry.Details.NONE, Optional.empty()));
    }

    private static Optional<java.time.Instant> day(String text) {
        try {
            return Optional.of(LocalDate.parse(text, DAY).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<java.time.Instant> minute(String text) {
        try {
            return Optional.of(LocalDateTime.parse(text, MINUTE).atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            return day(text.substring(0, Math.min(10, text.length())));
        }
    }
}
