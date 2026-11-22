package com.aresstack.spaceharvester.deck.ftp.jes;

import java.util.List;
import java.util.Optional;

/**
 * Die Ausgabe eines Jobs: seine Spool-Dateien und, wenn der Host keine Liste lieferte, die gesamte Ausgabe am Stück,
 * aus der die Abschnitte erkannt wurden (Verfahren „FAST“ aus MainframeMate).
 *
 * @param files Spool-Dateien
 * @param whole gesamte Ausgabe, wenn die Abschnitte daraus stammen
 */
public record JobOutput(List<SpoolFile> files, Optional<String> whole) {

    public JobOutput {
        files = List.copyOf(files);
        whole = whole == null ? Optional.empty() : whole;
    }

    /**
     * Lädt die Gliederung: zuerst die Liste des Hosts ({@code LIST jobId}); ist sie leer, die ganze Ausgabe
     * ({@code RETR jobId.x}) und daraus die Abschnitte.
     */
    public static JobOutput load(JesConnection jes, String jobId) throws java.io.IOException {
        List<SpoolFile> listed = jes.spoolFiles(jobId);
        if (!listed.isEmpty()) {
            return new JobOutput(listed, Optional.empty());
        }
        String all = jes.allSpool(jobId);
        return new JobOutput(JesListing.sections(all), Optional.of(all));
    }

    /** Der Text einer Spool-Datei: aus der ganzen Ausgabe geschnitten oder einzeln vom Host. */
    public String text(JesConnection jes, String jobId, SpoolFile file) throws java.io.IOException {
        if (whole.isPresent()) {
            return JesListing.sectionText(whole.get(), file.id());
        }
        return jes.spool(jobId, file.id());
    }
}
