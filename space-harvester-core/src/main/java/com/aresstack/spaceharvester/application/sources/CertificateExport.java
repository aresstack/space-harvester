package com.aresstack.spaceharvester.application.sources;

import java.nio.file.Path;
import java.util.List;

/**
 * Ergebnis des Zertifikatsexports einer Quelle (Strang ZX).
 *
 * @param file          das geschriebene ZIP
 * @param roots         importierbare Stammzertifikate
 * @param intermediates importierbare Zwischenzertifikate
 * @param missing       Zertifikate der Kette, die der Server nicht selbst mitschickt
 * @param serverTrusted ob die Kette des Servers an einem Anker der Quelle endet
 * @param problems      was fehlt und warum (Handshake gescheitert usw.), ohne Geheimnisse
 */
public record CertificateExport(Path file, long roots, long intermediates, long missing, boolean serverTrusted,
                                List<String> problems) {

    public CertificateExport {
        problems = List.copyOf(problems);
    }
}
