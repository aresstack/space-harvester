package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobTask;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Eine Art Hintergrundarbeit, etwa „Seiten nachladen“ oder „Bilder beschreiben“.
 *
 * @param id         Kennung, abgelegt als {@value BackgroundJobs#KIND} an jedem Unterauftrag
 * @param work       baut die Arbeit eines Unterauftrags allein aus seinem {@link BackgroundPart}; nach einem Neustart
 *                   geschieht das erneut aus den abgelegten Angaben, darum darf die Arbeit nichts brauchen, was nicht
 *                   in Quelle, Space und {@link BackgroundPart#parameters()} steht
 * @param autoResume ob unterbrochene Unteraufträge dieser Art jetzt von selbst weiterlaufen sollen, etwa nur bei
 *                   eingeschalteter Automatik und eingestelltem Modell; zur Laufzeit gefragt
 * @param exclusive  höchstens ein offener (wartender, angehaltener oder laufender) Unterauftrag je Quelle und Space,
 *                   für Arten, deren Teil seine Arbeit allein aus dem Bestand bestimmt und die neue Teile nur mit
 *                   {@link BackgroundJobs#startUnlessRunning} starten (Abnahme #1052): Fortsetzen lehnt
 *                   {@link BackgroundJobs#resumeJob} dann ab, solange ein anderer offen ist, und nach einem Neustart
 *                   setzt {@link BackgroundJobs#resumeInterrupted} nur den jüngsten unterbrochenen fort. Nicht für
 *                   Arten mit {@link BackgroundJobs#startUnlessWaiting}: Dort dürfen ein laufender und ein wartender
 *                   Teil nebeneinander stehen, und nach „Alle anhalten“ sperrten sich beide beim Fortsetzen gegenseitig
 * @param ownLane    Teile auf eigener Spur ({@link com.aresstack.spaceharvester.application.jobs.Jobs#OWN_LANE}): ohne
 *                   Platz unter „Vorbereitung“ und ohne Laufzeitgrenze. Kein freier Schalter (Abnahme Fundstelle 12):
 *                   nur für Arten, deren Teil selbst nichts rechnet, sondern die Arbeit eines eigenen, begrenzten
 *                   Ausführers sichtbar macht und über dessen Rückrufe wartet, ohne Thread; heute nur
 *                   {@link #OWN_LANE_KINDS}, begrenzt durch den Planer bzw. die Zeitgrenze der Prüfung
 */
public record BackgroundKind(String id, Function<BackgroundPart, JobTask> work, BooleanSupplier autoResume,
                             boolean exclusive, boolean ownLane) {

    /**
     * Arten mit abgenommener eigener Spur: Suchtreffer laden (Fundstelle 13), Hintergrundfüllung (12) und Verbindung
     * prüfen (17; die Prüfung läuft beim Aufrufer mit eigener Zeitgrenze).
     */
    public static final java.util.Set<String> OWN_LANE_KINDS = java.util.Set.of("search-hits", "fill", "connection");

    /** Ohne eigene Spur. */
    public BackgroundKind(String id, Function<BackgroundPart, JobTask> work, BooleanSupplier autoResume,
                          boolean exclusive) {
        this(id, work, autoResume, exclusive, false);
    }

    public BackgroundKind {
        Objects.requireNonNull(id, "id");
        if (ownLane && !OWN_LANE_KINDS.contains(id)) {
            // Abnahme Fundstellen 12 und 13: nur abgenommene Arten; eine weitere braucht eine eigene Abnahme.
            throw new IllegalArgumentException("Eigene Spur nur für Arten, die einen eigenen Ausführer sichtbar machen");
        }
        if (id.isBlank()) {
            throw new IllegalArgumentException("id darf nicht leer sein");
        }
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(autoResume, "autoResume");
    }

    /** Eine Art, von der je Quelle und Space mehrere Unteraufträge offen sein dürfen. */
    public BackgroundKind(String id, Function<BackgroundPart, JobTask> work, BooleanSupplier autoResume) {
        this(id, work, autoResume, false);
    }

    /** Eine Art, deren unterbrochene Unteraufträge immer von selbst weiterlaufen. */
    public BackgroundKind(String id, Function<BackgroundPart, JobTask> work) {
        this(id, work, () -> true);
    }

    /** Eine Art mit höchstens einem offenen Unterauftrag je Quelle und Space ({@link #exclusive()}). */
    public static BackgroundKind exclusive(String id, Function<BackgroundPart, JobTask> work,
                                           BooleanSupplier autoResume) {
        return new BackgroundKind(id, work, autoResume, true);
    }
}
