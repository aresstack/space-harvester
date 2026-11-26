package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.application.marks.SlideFrames;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Die Werte für Videos ({@code space-harvester-app.video.*}, nur die App): wie die Marken aus dem MP4-Index entstehen,
 * wann zwei Standbilder dieselbe Folie zeigen, wie Standbilder gezogen werden und wie der Player springt. Jeder Wert
 * hat einen Default und Grenzen; Laden, Schreiben und der Bereich „Video“ der Einstellungen lesen alle dieselbe Liste
 * {@link Value}. Zeiten stehen in Millisekunden.
 */
public final class VideoSettings {

    /** Präfix der Schlüssel in der Konfigurationsdatei. */
    public static final String KEY = "space-harvester-app.video";

    /** Ein einstellbarer Wert: Schlüssel unter {@link #KEY}, Bezeichnung, Hinweis, Default und Grenzen. */
    public enum Value implements NumberSetting {
        SIZE_FACTOR("slide-size-factor", "Größenfaktor eines Folienwechsels",
                "Ein Bild mindestens so viele Male so groß wie der Median aller Bilder gilt als Folienwechsel",
                20, 2, 1000),
        MIN_CHANGE_BYTES("slide-min-change-bytes", "Mindestgröße eines Folienwechsels (Bytes)",
                "Kleinere Bilder sind nie ein Folienwechsel, auch wenn der Median winzig ist", 2048, 1, 10_000_000),
        BURST_MILLIS("slide-burst-millis", "Zusammengehörige Änderungen (ms)",
                "Große Bilder in diesem Abstand sind ein Wechsel, etwa eine Animation oder ein bewegtes Fenster",
                3000, 1, 600_000),
        START_MILLIS("slide-start-millis", "Erste Folie bis (ms)",
                "Bis hierhin zeigt das Video die erste Folie, ein großes Bild ist noch kein Wechsel", 1500, 0,
                600_000),
        MAX_MARKS_PER_MINUTE("max-marks-per-minute", "Höchstens Marken je Minute",
                "Sicherung für Videos, die keine Präsentation sind: findet der Index mehr Wechsel, bleiben nur die "
                        + "stärksten (Szenenwechsel)", 6, 1, 600),
        MAX_MARKS("max-marks", "Höchstens Marken je Video",
                "Mehr Marken bekommt kein Video, auch kein sehr langes; es bleiben die stärksten Wechsel",
                SlideFrames.Thresholds.DEFAULT.maxFrames(), 1, 10_000),
        NEXT_SKIP_MILLIS("next-skip-millis", "Nächste Marke ab (ms)",
                "„Nächste Folie“ überspringt eine Marke, die höchstens so weit nach der Position liegt", 500, 0,
                60_000),
        PREVIOUS_GRACE_MILLIS("previous-grace-millis", "Vorige Folie innerhalb (ms)",
                "So kurz nach einem Wechsel springt „Vorige Folie“ zur Folie davor statt an den Beginn der aktuellen",
                2000, 0, 60_000),
        BLOCK_PIXELS("same-slide-block-pixels", "Blockgröße beim Vergleich (Pixel)",
                "Zwei Standbilder werden im Raster solcher Blöcke verglichen, darin verschwindet Kompressionsrauschen",
                SlideFrames.Thresholds.DEFAULT.blockPixels(), 2, 256),
        CHANGED_GRAY("same-slide-gray", "Abweichung eines Blocks (Graustufen)",
                "Ab dieser Abweichung seines Mittels gilt ein Block als geändert",
                (long) SlideFrames.Thresholds.DEFAULT.changedGray(), 1, 255),
        POINTER_BLOCKS("pointer-blocks", "Größe des Mauszeigers (Blöcke)",
                "So viele Blöcke breit und hoch ist eine Stelle, die nur der Mauszeiger geändert haben kann",
                SlideFrames.Thresholds.DEFAULT.pointerBlocks(), 1, 100),
        POINTER_SPOTS("pointer-spots", "Höchstens Zeigerstellen",
                "Weicht ein Standbild nur an so vielen zeigergroßen Stellen ab, ist es dieselbe Folie",
                SlideFrames.Thresholds.DEFAULT.pointerSpots(), 0, 100),
        SETTLE_MILLIS("frame-settle-millis", "Standbild nach dem Wechsel (ms)",
                "So lange nach einem Folienwechsel wird das Standbild gezogen, wenn die neue Folie sicher steht",
                2000, 0, 60_000),
        FRAME_TIMEOUT_MILLIS("frame-timeout-millis", "Frist je Standbild (ms)",
                "So lange wartet ein Standbild höchstens auf VLC", 30_000, 1000, 600_000),
        QUIET_MILLIS("frame-quiet-millis", "Ruhe nach dem Start (ms)",
                "Nach dem Anhalten so lange Ruhe, bevor das erste Standbild gezogen wird", 400, 0, 60_000),
        FRAMES_AFTER_SEEK("frames-after-seek", "Bilder nach einem Sprung",
                "So viele Bilder Wiedergabe nach einem Sprung, bevor eines als Standbild gilt", 10, 1, 1000),
        EARLY_TOLERANCE_MILLIS("frame-early-tolerance-millis", "Toleranz vor der Stelle (ms)",
                "So weit darf die Position beim Standbild vor der angesprungenen Stelle liegen", 500, 0, 60_000),
        LATE_TOLERANCE_MILLIS("frame-late-tolerance-millis", "Toleranz nach der Stelle (ms)",
                "So weit darf die Position beim Standbild oder nach einem Sprung im Player nach der Stelle liegen",
                1500, 0, 60_000),
        POLL_MILLIS("poll-millis", "Abfrageabstand beim Warten auf VLC (ms)",
                "In diesem Abstand prüfen Standbilder und Player, ob VLC ein Bild geliefert, angehalten oder die "
                        + "angesprungene Stelle erreicht hat",
                100, 1, 10_000),
        PAUSE_WAIT_MILLIS("pause-wait-millis", "Längstens auf das Anhalten warten (ms)",
                "So lange wartet das Ziehen von Standbildern beim Start höchstens, bis VLC angehalten hat", 5000, 0,
                600_000),
        PAUSE_RETRY_MILLIS("pause-retry-millis", "Anhalten erneut verlangen nach (ms)",
                "Hält VLC beim Start nicht an, wird es in diesem Abstand erneut verlangt", 1000, 1, 600_000),
        START_TIMEOUT_MILLIS("start-timeout-millis", "Frist für den Start (ms)",
                "Startet VLC in dieser Zeit nicht, meldet der Player einen Fehler", 15_000, 1000, 600_000),
        SKIP_SECONDS("skip-seconds", "Sprung zurück/vor (Sekunden)",
                "So weit springen die Knöpfe mit den Pfeilen im Kreis zurück bzw. vor", 5, 1, 3600),
        SEEK_FEEDBACK_MILLIS("seek-feedback-millis", "Frist für einen Sprung (ms)",
                "Erreicht VLC die angesprungene Stelle in dieser Zeit nicht, meldet der Player es", 10_000, 1000,
                600_000),
        SEEK_REACHED_MILLIS("seek-reached-millis", "Stelle erreicht innerhalb (ms)",
                "So nah an der angesprungenen Stelle gilt sie als erreicht", 2500, 0, 60_000),
        SEEK_REACHED_PERMILLE("seek-reached-permille", "Stelle erreicht innerhalb (‰ der Länge)",
                "Kennt VLC die Länge nicht, gilt die Stelle so nah (in Promille des Videos) als erreicht", 20, 0,
                1000),
        PROGRESS_MILLIS("progress-millis", "Zeitleiste aktualisieren alle (ms)",
                "In diesem Abstand liest der Player Position und Länge für die Zeitleiste", 350, 50, 10_000),
        BRIDGE_READ_AHEAD("bridge-read-ahead", "Vorauslesen beim Abspielen (Blöcke à 256 KiB)",
                "So viele Blöcke liest der Player hinter jedem gelieferten voraus", 8, 0, 1000),
        BRIDGE_FETCHERS("bridge-fetchers", "Parallele Abrufe beim Abspielen",
                "So viele Bereichsanfragen stellt ein Player (oder das Ziehen von Standbildern) zugleich an die "
                        + "Quelle; jede ist von der Laufzeit begrenzt, nicht von der Bandbreite", 4, 1, 64),
        BRIDGE_CACHED("bridge-cached", "Blöcke im Speicher beim Abspielen",
                "So viele Blöcke à 256 KiB hält ein Player, damit ein Sprung zurück nicht neu lädt", 128, 1, 100_000),
        BRIDGE_WORKERS("bridge-workers", "Gleichzeitige Anfragen von VLC",
                "So viele Anfragen von VLC an die lokale Brücke werden zugleich bedient", 3, 1, 64),
        AUDIO_PARALLEL("audio-parallel", "Parallele Abrufe der Audiospur",
                "So viele Bereichsanfragen stellen Transkription und Nur-Audio zusammen zugleich an die Quelle; über "
                        + "BSCW am schnellsten mit 8, so viele Verbindungen hat eine Quelle", 8, 1, 64),
        AUDIO_AHEAD_REQUESTS("audio-ahead-requests", "Audio vorauslesen (Anfragen)",
                "So viele Anfragen der Audiospur werden vor der gelesenen geholt", 24, 0, 100_000),
        AUDIO_AHEAD_BYTES("audio-ahead-bytes", "Audio vorauslesen höchstens (Bytes)",
                "So viele Bytes der Audiospur liegen höchstens vor der gelesenen bereit", 6L * 1024 * 1024, 0,
                1024L * 1024 * 1024),
        AUDIO_MAX_GAP("audio-max-gap-bytes", "Videobytes zwischen Audioteilen mitlesen (Bytes)",
                "Liegen höchstens so viele Videobytes zwischen zwei Audioteilen, holt eine Anfrage beide; spart "
                        + "Anfragen auf Kosten von Bandbreite", 32 * 1024, 0, 262_144),
        INDEX_READERS("index-readers", "Parallele Abrufe des Video-Index",
                "So viele Teile des MP4-Index werden zugleich gelesen", 4, 1, 64),
        PREFETCH_CACHED("prefetch-cached", "Vorgeladene Anhänge im Speicher",
                "So viele vorgeladene Anhänge und Standbilder einer Seite hält die App im Speicher", 24, 1, 10_000),
        PREFETCH_BATCH("prefetch-batch", "Anhangsliste aktualisieren nach",
                "Nach so vielen im Hintergrund geladenen Anhängen oder Standbildern zeigt die Liste den neuen Stand",
                10, 1, 10_000);

        private final String key;
        private final String label;
        private final String hint;
        private final long defaultValue;
        private final long min;
        private final long max;

        Value(String key, String label, String hint, long defaultValue, long min, long max) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String hint() {
            return hint;
        }

        @Override
        public long defaultValue() {
            return defaultValue;
        }

        @Override
        public long min() {
            return min;
        }

        @Override
        public long max() {
            return max;
        }
    }

    /** Alle Werte mit ihren Defaults. */
    public static final VideoSettings DEFAULT = new VideoSettings(Map.of());

    private final Map<Value, Long> values;

    /**
     * @param values abweichende Werte; fehlende haben ihren Default
     * @throws IllegalArgumentException wenn ein Wert außerhalb seiner Grenzen liegt
     */
    public VideoSettings(Map<Value, Long> values) {
        Objects.requireNonNull(values, "values");
        EnumMap<Value, Long> all = new EnumMap<>(Value.class);
        for (Value value : Value.values()) {
            long chosen = values.getOrDefault(value, value.defaultValue());
            if (!value.accepts(chosen)) {
                throw new IllegalArgumentException(KEY + "." + value.key() + " muss zwischen " + value.min()
                        + " und " + value.max() + " liegen");
            }
            all.put(value, chosen);
        }
        this.values = java.util.Collections.unmodifiableMap(all);
    }

    public long get(Value value) {
        return values.get(value);
    }

    public int number(Value value) {
        return (int) get(value);
    }

    public Duration duration(Value value) {
        return Duration.ofMillis(get(value));
    }

    /** Die vom Default abweichenden Werte, in der Reihenfolge der Liste. */
    public List<Value> changed() {
        return java.util.Arrays.stream(Value.values()).filter(value -> get(value) != value.defaultValue()).toList();
    }

    /** Dieselben Werte mit {@code value} geändert. */
    public VideoSettings with(Value value, long chosen) {
        EnumMap<Value, Long> next = new EnumMap<>(values);
        next.put(value, chosen);
        return new VideoSettings(next);
    }

    /** Die Werte, mit denen der Core zwei Standbilder vergleicht. */
    public SlideFrames.Thresholds slideThresholds() {
        return new SlideFrames.Thresholds(number(Value.BLOCK_PIXELS), get(Value.CHANGED_GRAY),
                number(Value.POINTER_BLOCKS), number(Value.POINTER_SPOTS), number(Value.MAX_MARKS));
    }

    /** Der Block {@code video:} unter {@code space-harvester-app} mit den abweichenden Werten; ohne {@code null}. */
    public String yaml() {
        if (changed().isEmpty()) {
            return null;
        }
        StringBuilder yaml = new StringBuilder("video:");
        for (Value value : changed()) {
            yaml.append("\n  ").append(value.key()).append(": ").append(get(value));
        }
        return yaml.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VideoSettings settings && settings.values.equals(values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "VideoSettings" + values;
    }
}
