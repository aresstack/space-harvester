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
 */
public record BackgroundKind(String id, Function<BackgroundPart, JobTask> work, BooleanSupplier autoResume) {

    public BackgroundKind {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id darf nicht leer sein");
        }
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(autoResume, "autoResume");
    }

    /** Eine Art, deren unterbrochene Unteraufträge immer von selbst weiterlaufen. */
    public BackgroundKind(String id, Function<BackgroundPart, JobTask> work) {
        this(id, work, () -> true);
    }
}
