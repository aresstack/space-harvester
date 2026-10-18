package com.aresstack.spaceharvester.application.reading;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Die Lese-Ansicht einer Seite für einen Aufrufer (#176).
 *
 * @param header     Kopfdaten der Seite, gegen die Rechte und Version geprüft wurden
 * @param status     ob die Ansicht vorliegt; nur bei {@link Status#AVAILABLE} sind {@code html}, {@code text} und
 *                   {@code images} gefüllt
 * @param version    Version der Seite, aus der die Ansicht stammt (gleich der von {@code header})
 * @param html       gerenderter HTML-Körper der Quelle mit ausgeführten Makros
 * @param baseUri    Basis für relative Adressen im HTML
 * @param text       Lesetext für Leser und KI, aus der gerenderten Ansicht
 * @param images     Bilder der Ansicht; die Bytes liefert die Bildbeschaffung, nie dieser Anwendungsfall
 * @param foreign    was die Quelle von außerhalb der Seite einbindet; bei {@link Status#RESTRICTED} der Grund
 * @param stored     {@code true}, wenn die Ansicht aus der Ablage kommt, {@code false} bei einem neuen Abruf
 * @param renderedAt wann die Quelle die Ansicht geliefert hat; {@code null} ohne Ansicht
 */
public record ReadingView(PageHeader header, Status status, String version, String html, String baseUri,
                          String text, List<HarvestSource.ViewImage> images, ForeignContent foreign, boolean stored,
                          Instant renderedAt) {

    /** Ob die Ansicht vorliegt. */
    public enum Status {
        /** Ansicht, Lesetext und Bilder liegen vor. */
        AVAILABLE,
        /**
         * Die Seite bindet Inhalte anderer Seiten oder Systeme ein, die der Aufrufer nicht nachweislich lesen darf;
         * die Ansicht wird ihm nicht gezeigt. Lesbar bleibt die Seite selbst ({@code body.storage}).
         */
        RESTRICTED,
        /** Die Quelle hat keine Lese-Ansicht (etwa eine Dateiquelle). */
        UNSUPPORTED
    }

    public ReadingView {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(foreign, "foreign");
        html = html == null ? "" : html;
        baseUri = baseUri == null ? "" : baseUri;
        text = text == null ? "" : text;
        images = List.copyOf(images);
        if (status != Status.AVAILABLE && (!html.isEmpty() || !text.isEmpty() || !images.isEmpty())) {
            throw new IllegalArgumentException("Ohne Ansicht keine Inhalte");
        }
    }

    static ReadingView without(PageHeader header, Status status, ForeignContent foreign) {
        return new ReadingView(header, status, header.metadata().version(), "", "", "", List.of(), foreign, false,
                null);
    }
}
