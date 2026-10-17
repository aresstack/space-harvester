package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Fachliche Bezeichnungen und Formate der Oberfläche, auf Deutsch. */
public final class Texts {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());

    private Texts() {
    }

    public static String time(Instant instant) {
        return instant == null ? "–" : TIME.format(instant);
    }

    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.GERMANY, "%.1f %s", value, units[unit]);
    }

    public static String duration(Instant from, Instant to) {
        if (from == null) {
            return "–";
        }
        Duration d = Duration.between(from, to == null ? Instant.now() : to);
        long seconds = Math.max(0, d.getSeconds());
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3600) {
            return seconds / 60 + " min " + seconds % 60 + " s";
        }
        return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
    }

    public static String state(JobState state) {
        return switch (state) {
            case QUEUED -> "Wartet";
            case RUNNING -> "Läuft";
            case COMPLETED -> "Fertig";
            case FAILED -> "Fehlgeschlagen";
            case CANCELLED -> "Abgebrochen";
        };
    }

    public static Comic.Tone tone(JobState state) {
        return switch (state) {
            case QUEUED, RUNNING -> Comic.Tone.RUNNING;
            case COMPLETED -> Comic.Tone.GOOD;
            case FAILED -> Comic.Tone.PROBLEM;
            case CANCELLED -> Comic.Tone.WARNING;
        };
    }

    public static String type(JobType type) {
        return switch (type) {
            case HARVEST -> "Ernte";
            case SYNC -> "Abgleich";
            case EXPORT -> "Export";
        };
    }

    public static String cacheState(CacheState state) {
        return switch (state) {
            case CONFIRMED -> "Bestätigt";
            case INCOMPLETE -> "Unvollständig";
            case STALE -> "Veraltet";
        };
    }

    /** Zustand einer Generation in einem Satz. */
    public static String generation(CacheGeneration generation) {
        if (generation == null) {
            return "Kein Bestand";
        }
        return cacheState(generation.state()) + ", abgeglichen " + time(generation.syncedAt()) + ", zuletzt geprüft "
                + time(generation.lastCheckedAt());
    }

    public static String capability(SourceCapability capability) {
        return switch (capability) {
            case PARTITIONS -> "Spaces auflisten";
            case PAGE_LISTING -> "Seitenlisten";
            case PAGE_METADATA -> "Seitenmetadaten";
            case STORED_PAGE -> "Originalseiten";
            case ASYNC_STORED_PAGE -> "Originalseiten asynchron";
            case PAGE_ATTACHMENTS -> "Anhänge";
            case LIVE_SEARCH -> "Volltextsuche in der Quelle";
        };
    }

    public static String sourceType(String type) {
        return switch (type) {
            case "confluence" -> "Confluence";
            case "files" -> "Lokale Dateien";
            default -> type;
        };
    }

    public static String auth(SourceSettingsConfig.Auth auth) {
        return switch (auth.type()) {
            case NONE -> "Ohne Anmeldung";
            case BASIC -> "Benutzer und Passwort (" + (auth.username() == null ? "ohne Benutzer" : auth.username()) + ")";
            case PAT -> "Personal Access Token";
        };
    }

    public static String shortHash(String hash) {
        if (hash == null) {
            return "–";
        }
        int colon = hash.indexOf(':');
        String bare = colon >= 0 ? hash.substring(colon + 1) : hash;
        return bare.length() > 12 ? bare.substring(0, 12) + "…" : bare;
    }

    public static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }
}
