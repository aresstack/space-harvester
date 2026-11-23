package com.aresstack.spaceharvester.deck.ftp.jes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Die Ausgabe eines Jobs: seine Spool-Dateien und, wenn sie aus der Gesamtausgabe stammen, diese am Stück. Wie die
 * DD-Namen ermittelt werden, bestimmt {@link SpoolNaming} (FAST, PROBE, OFF wie in MainframeMate).
 *
 * @param files Spool-Dateien
 * @param whole gesamte Ausgabe, wenn die Abschnitte daraus stammen
 */
public record JobOutput(List<SpoolFile> files, Optional<String> whole) {

    /** Nach so vielen Fehlversuchen in Folge gibt es keine weiteren Spool-Dateien (MainframeMate). */
    static final int MAX_MISSES = 3;
    /** Sicherheitsgrenze für das Abrufen einzeln (MainframeMate). */
    static final int MAX_PROBE = 200;

    public JobOutput {
        files = List.copyOf(files);
        whole = whole == null ? Optional.empty() : whole;
    }

    /** Wie {@link #load(JesConnection, String, SpoolNaming)} mit „FAST“. */
    public static JobOutput load(JesConnection jes, String jobId) throws IOException {
        return load(jes, jobId, SpoolNaming.DEFAULT);
    }

    /**
     * Lädt die Gliederung.
     * <ul>
     *   <li>OFF: sofort die Gesamtausgabe, Abschnitte als {@code SPOOL#n}; die Namen lädt der Aufrufer nach.</li>
     *   <li>sonst zuerst die Liste des Hosts ({@code LIST jobId}); ist sie leer, bei PROBE jede Datei einzeln
     *   ({@code RETR jobId.n} bis drei Fehlversuche in Folge), sonst bzw. wenn auch das nichts bringt die Gesamtausgabe
     *   ({@code RETR jobId.x}) und daraus die Abschnitte.</li>
     * </ul>
     */
    public static JobOutput load(JesConnection jes, String jobId, SpoolNaming naming) throws IOException {
        if (naming.mode() == SpoolNaming.Mode.OFF) {
            String all = jes.allSpool(jobId);
            return new JobOutput(numbered(JesListing.sections(all)), Optional.of(all));
        }
        List<SpoolFile> listed = jes.spoolFiles(jobId);
        if (!listed.isEmpty()) {
            return new JobOutput(listed, Optional.empty());
        }
        if (naming.mode() == SpoolNaming.Mode.PROBE) {
            List<SpoolFile> probed = probe(jes, jobId);
            if (!probed.isEmpty()) {
                return new JobOutput(probed, Optional.empty());
            }
        }
        String all = jes.allSpool(jobId);
        return new JobOutput(JesListing.sections(all), Optional.of(all));
    }

    /** Ruft {@code jobId.1}, {@code jobId.2} … einzeln ab, bis drei in Folge fehlen. */
    static List<SpoolFile> probe(JesConnection jes, String jobId) throws IOException {
        List<SpoolFile> result = new ArrayList<>();
        int misses = 0;
        for (int id = 1; id <= MAX_PROBE && misses < MAX_MISSES; id++) {
            Optional<SpoolFile> file = jes.probe(jobId, id);
            if (file.isPresent()) {
                result.add(file.get());
                misses = 0;
            } else {
                misses++;
            }
        }
        return result;
    }

    private static List<SpoolFile> numbered(List<SpoolFile> sections) {
        List<SpoolFile> result = new ArrayList<>(sections.size());
        for (SpoolFile file : sections) {
            result.add(new SpoolFile(file.id(), "SPOOL#" + file.id(), file.stepName(), file.procStep(),
                    file.outputClass(), file.bytes(), file.records()));
        }
        return result;
    }

    /** Dieselbe Ausgabe mit nachgeladenen DD-Namen; {@code SPOOL#n} aus der Probe ersetzt nichts. */
    public JobOutput withNames(Map<Integer, String> names) {
        List<SpoolFile> renamed = new ArrayList<>(files.size());
        for (SpoolFile file : files) {
            String name = names.get(file.id());
            renamed.add(name == null || name.startsWith("SPOOL#") ? file : new SpoolFile(file.id(), name,
                    file.stepName(), file.procStep(), file.outputClass(), file.bytes(), file.records()));
        }
        return new JobOutput(renamed, whole);
    }

    /** Der Text einer Spool-Datei: aus der ganzen Ausgabe geschnitten oder einzeln vom Host. */
    public String text(JesConnection jes, String jobId, SpoolFile file) throws IOException {
        if (whole.isPresent()) {
            return JesListing.sectionText(whole.get(), file.id());
        }
        return jes.spool(jobId, file.id());
    }
}
